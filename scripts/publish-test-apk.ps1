[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("patch", "minor", "major")]
    [string] $Bump,

    [string] $Notes
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $script:Utf8NoBom
[Console]::OutputEncoding = $script:Utf8NoBom
$OutputEncoding = $script:Utf8NoBom
$env:PYTHONUTF8 = "1"
$env:PYTHONIOENCODING = "utf-8"

$Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$VersionFile = Join-Path $Root "version.properties"
$ApkDirectory = [IO.DirectoryInfo] (Join-Path $Root "apk")
$ChangelogFile = Join-Path $ApkDirectory.FullName "ver_change_log.md"
$ReleaseDate = Get-Date -Format "yyyy-MM-dd"

function ConvertFrom-VersionPropertiesLines {
    param([Parameter(Mandatory = $true)][string[]] $Lines)

    $properties = @{}
    foreach ($line in $Lines) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }
        if ($trimmed -notmatch "^(?<key>[A-Z0-9_]+)=(?<value>.*)$") {
            throw "Invalid version property line"
        }
        $key = $Matches["key"]
        if ($properties.ContainsKey($key)) {
            throw "Duplicate version property: $key"
        }
        $properties[$key] = $Matches["value"].Trim()
    }

    foreach ($required in @("SOIM_VERSION_NAME", "SOIM_VERSION_CODE")) {
        if (-not $properties.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($properties[$required])) {
            throw "Missing version property: $required"
        }
    }
    return $properties
}

function Read-VersionProperties {
    param([Parameter(Mandatory = $true)][string] $Path)

    if (-not [IO.File]::Exists($Path)) {
        throw "Missing version source: $Path"
    }
    return ConvertFrom-VersionPropertiesLines -Lines @(
        [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8)
    )
}

function Get-CommittedVersionProperties {
    Push-Location $Root
    try {
        $result = Invoke-CapturedNative -FilePath "git" -ArgumentList @(
            "show",
            "HEAD:version.properties"
        )
    }
    finally {
        Pop-Location
    }
    if ($result.ExitCode -ne 0) {
        throw "Unable to read committed version source: $($result.Output)"
    }
    return ConvertFrom-VersionPropertiesLines -Lines @($result.Output -split "`r?`n")
}

function Get-CandidateVersion {
    param(
        [Parameter(Mandatory = $true)][string] $CurrentVersion,
        [Parameter(Mandatory = $true)][string] $BumpKind
    )

    if ($CurrentVersion -notmatch "^(?<major>0|[1-9][0-9]*)\.(?<minor>0|[1-9][0-9]*)\.(?<patch>0|[1-9][0-9]*)$") {
        throw "SOIM_VERSION_NAME is not strict SemVer: $CurrentVersion"
    }

    [long] $major = $Matches["major"]
    [long] $minor = $Matches["minor"]
    [long] $patch = $Matches["patch"]
    switch ($BumpKind.ToLowerInvariant()) {
        "patch" { $patch += 1 }
        "minor" { $minor += 1; $patch = 0 }
        "major" { $major += 1; $minor = 0; $patch = 0 }
        default { throw "Unsupported version bump: $BumpKind" }
    }
    return "$major.$minor.$patch"
}

function Get-CurrentMarker {
    param(
        [Parameter(Mandatory = $true)][IO.DirectoryInfo] $Directory,
        [Parameter(Mandatory = $true)][string] $ExpectedVersion
    )

    $markerFiles = @($Directory.GetFiles("current_version_is_*"))
    if ($markerFiles.Count -ne 1) {
        throw "Expected exactly one current-version marker, found $($markerFiles.Count)"
    }
    $expectedName = "current_version_is_$ExpectedVersion"
    if ($markerFiles[0].Name -cne $expectedName) {
        throw "Version marker mismatch: expected $expectedName, found $($markerFiles[0].Name)"
    }
    return $markerFiles[0]
}

function Resolve-NotesFile {
    param([Parameter(Mandatory = $true)][string] $Path)

    $candidate = $Path
    if (-not [IO.Path]::IsPathRooted($candidate)) {
        $candidate = Join-Path $Root $candidate
    }
    $resolved = Resolve-Path -LiteralPath $candidate -ErrorAction Stop
    if (-not [IO.File]::Exists($resolved.Path)) {
        throw "Notes must be a file: $Path"
    }
    $content = [IO.File]::ReadAllText($resolved.Path, [Text.Encoding]::UTF8).Trim()
    if ([string]::IsNullOrWhiteSpace($content)) {
        throw "Notes must not be empty"
    }
    return $content
}

function Assert-PublicationTargetsClean {
    param([Parameter(Mandatory = $true)][string[]] $RelativePaths)

    Push-Location $Root
    try {
        & git diff --quiet HEAD -- @RelativePaths
        if ($LASTEXITCODE -ne 0) {
            throw "Tracked publication files contain unstaged changes"
        }
        & git diff --cached --quiet -- @RelativePaths
        if ($LASTEXITCODE -ne 0) {
            throw "Tracked publication files contain staged changes"
        }
    }
    finally {
        Pop-Location
    }
}

function Get-SdkDirectory {
    $localProperties = Join-Path $Root "local.properties"
    $sdkMatches = @(Select-String -LiteralPath $localProperties -Pattern "^\s*sdk\.dir\s*=" -ErrorAction Stop)
    if ($sdkMatches.Count -ne 1) {
        throw "local.properties must contain exactly one sdk.dir"
    }
    $sdkPath = ($sdkMatches[0].Line -split "=", 2)[1].Trim()
    $sdkPath = $sdkPath -replace "\\:", ":" -replace "\\\\", "\"
    if (-not [IO.Directory]::Exists($sdkPath)) {
        throw "Android SDK directory does not exist"
    }
    return [IO.Path]::GetFullPath($sdkPath)
}

function Get-AndroidTools {
    param([Parameter(Mandatory = $true)][string] $SdkDirectory)

    $buildToolsRoot = Join-Path $SdkDirectory "build-tools"
    $stableVersions = @(
        Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
            Where-Object { $_.Name -match "^[0-9]+\.[0-9]+\.[0-9]+$" } |
            Sort-Object { [Version] $_.Name } -Descending
    )
    foreach ($directory in $stableVersions) {
        $aapt = Join-Path $directory.FullName "aapt.exe"
        $apksigner = Join-Path $directory.FullName "apksigner.bat"
        if ([IO.File]::Exists($aapt) -and [IO.File]::Exists($apksigner)) {
            $adb = Join-Path $SdkDirectory "platform-tools\adb.exe"
            return [pscustomobject] @{
                BuildToolsVersion = $directory.Name
                Aapt = $aapt
                ApkSigner = $apksigner
                Adb = $adb
            }
        }
    }
    throw "No stable Android build-tools installation contains aapt.exe and apksigner.bat"
}

function Invoke-CapturedNative {
    param(
        [Parameter(Mandatory = $true)][string] $FilePath,
        [Parameter(Mandatory = $true)][string[]] $ArgumentList
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $lines = @(& $FilePath @ArgumentList 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    return [pscustomobject] @{
        ExitCode = $exitCode
        Output = ($lines -join [Environment]::NewLine)
    }
}

function Invoke-GradleGate {
    param(
        [Parameter(Mandatory = $true)][string] $CandidateVersion,
        [Parameter(Mandatory = $true)][int] $CandidateCode
    )

    $gradleWrapper = Join-Path $Root "gradlew.bat"
    $arguments = @(
        "-PsoimVersionName=$CandidateVersion",
        "-PsoimVersionCode=$CandidateCode",
        "clean",
        ":app:testDebugUnitTest",
        ":app:testReleaseUnitTest",
        ":app:lintDebug",
        ":app:compileDebugAndroidTestKotlin",
        ":app:assembleDebug",
        ":app:assembleRelease",
        "--stacktrace"
    )
    Push-Location $Root
    try {
        & $gradleWrapper @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle publication gate failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

function Test-ApkMetadata {
    param(
        [Parameter(Mandatory = $true)][string] $Aapt,
        [Parameter(Mandatory = $true)][string] $ApkPath,
        [Parameter(Mandatory = $true)][string] $ExpectedVersion,
        [Parameter(Mandatory = $true)][int] $ExpectedCode
    )

    $result = Invoke-CapturedNative -FilePath $Aapt -ArgumentList @("dump", "badging", $ApkPath)
    if ($result.ExitCode -ne 0) {
        throw "aapt.exe dump badging failed: $($result.Output)"
    }
    $expectedTokens = @(
        "package: name='cn.soul2.imageai'",
        "versionName='$ExpectedVersion'",
        "versionCode='$ExpectedCode'",
        "sdkVersion:'29'",
        "targetSdkVersion:'36'"
    )
    $expectedMinSdk = "minSdkVersion:'29'"
    foreach ($token in $expectedTokens) {
        if (-not $result.Output.Contains($token)) {
            throw "APK metadata mismatch; expected $token ($expectedMinSdk)"
        }
    }
    return $result.Output
}

function Test-DebugSignature {
    param(
        [Parameter(Mandatory = $true)][string] $ApkSigner,
        [Parameter(Mandatory = $true)][string] $ApkPath
    )

    $result = Invoke-CapturedNative -FilePath $ApkSigner -ArgumentList @(
        "verify",
        "--print-certs",
        $ApkPath
    )
    if ($result.ExitCode -ne 0) {
        throw "apksigner.bat verify failed: $($result.Output)"
    }
    if ($result.Output -notmatch "CN=Android Debug") {
        throw "Debug APK is not signed by the Android Debug certificate"
    }
    return $result.Output
}

function Test-ReleaseUnsigned {
    param(
        [Parameter(Mandatory = $true)][string] $Aapt,
        [Parameter(Mandatory = $true)][string] $ApkSigner,
        [Parameter(Mandatory = $true)][string] $ApkPath,
        [Parameter(Mandatory = $true)][string] $ExpectedVersion,
        [Parameter(Mandatory = $true)][int] $ExpectedCode
    )

    [void] (Test-ApkMetadata `
        -Aapt $Aapt `
        -ApkPath $ApkPath `
        -ExpectedVersion $ExpectedVersion `
        -ExpectedCode $ExpectedCode)
    $result = Invoke-CapturedNative -FilePath $ApkSigner -ArgumentList @("verify", $ApkPath)
    if ($result.ExitCode -eq 0) {
        throw "Release APK unexpectedly contains a valid signature"
    }
    $unsignedDiagnosticPattern = (
        [regex]::Escape("Missing META-INF/MANIFEST.MF") +
            "|No signatures|not signed"
    )
    $isUnsigned =
        $result.Output -match "DOES NOT VERIFY" -and
        $result.Output -match "(?i)($unsignedDiagnosticPattern)"
    if (-not $isUnsigned) {
        throw "Unsigned release diagnostic was not recognized: $($result.Output)"
    }
}

function Test-ApkSecurity {
    param(
        [Parameter(Mandatory = $true)][string] $Aapt,
        [Parameter(Mandatory = $true)][string] $ApkPath
    )

    $listing = Invoke-CapturedNative -FilePath $Aapt -ArgumentList @("list", $ApkPath)
    if ($listing.ExitCode -ne 0) {
        throw "Unable to inspect APK entries"
    }
    $forbiddenEntry = "(?i)(apikey|local\.properties|secrets\.properties|\.jks$|\.keystore$)"
    if ($listing.Output -match $forbiddenEntry) {
        throw "Sensitive file name is packaged in the APK"
    }

    $apkText = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($ApkPath))
    foreach ($token in @("AI_API_KEY", "apikey.txt", "aiApiKey")) {
        if ($apkText.Contains($token)) {
            throw "Forbidden secret-injection token is present in the APK: $token"
        }
    }
}

function Test-LatestRoomSchema {
    $schemaDirectory = Join-Path $Root "app\schemas\cn.soul2.imageai.data.db.AppDatabase"
    $schemaFiles = @(Get-ChildItem -LiteralPath $schemaDirectory -Filter "*.json" -File)
    if ($schemaFiles.Count -eq 0) {
        throw "No exported Room schema found"
    }
    $latest = $schemaFiles | Sort-Object { [int] $_.BaseName } -Descending | Select-Object -First 1
    if ($latest.BaseName -cne "6") {
        throw "Latest Room schema filename must be 6.json"
    }
    $schemaBytes = [IO.File]::ReadAllBytes($latest.FullName)
    $schemaText = [Text.Encoding]::UTF8.GetString($schemaBytes)
    $schema = $schemaText | ConvertFrom-Json
    if ([int] $schema.database.version -ne 6) {
        throw "Latest Room schema database version must be 6"
    }
    $tables = @($schema.database.entities | ForEach-Object { $_.tableName } | Sort-Object)
    $expected = @(
        "active_image_analysis",
        "ai_runtime_setting",
        "analysis_activation_diagnostic",
        "analysis_term",
        "app_setting",
        "effective_image_metadata",
        "effective_image_term",
        "image",
        "image_analysis",
        "image_search_term",
        "image_user_correction",
        "media_sync_checkpoint",
        "media_sync_run",
        "model_profile",
        "protocol_definition",
        "provider_profile",
        "search_document",
        "search_document_fts",
        "search_gram",
        "search_source_chunk",
        "search_term",
        "search_term_alias",
        "search_text_alias_chunk",
        "user_term_override"
    ) | Sort-Object
    if (($tables -join "|") -cne ($expected -join "|")) {
        throw "Unexpected Room schema tables: $($tables -join ', ')"
    }
    if ($schemaText -match "(?i)(fts5|image_fts)") {
        throw "Unsupported FTS5 schema detected; SoIM currently requires the Room FTS4 projection"
    }
}

function Get-ApkSha256 {
    param([Parameter(Mandatory = $true)][string] $Path)

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Parse-AdbDeviceRows {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string] $Output
    )

    $rows = @()
    $statePattern = (
        "^(?<serial>[^\s*]\S*)\s+" +
            "(?<state>device|offline|unauthorized|recovery|sideload|bootloader|" +
            "host|unknown|connecting|authorizing|no permissions)(?:\s|$)"
    )
    foreach ($line in @($Output -split "`r?`n")) {
        $trimmed = $line.Trim()
        if ($trimmed -match $statePattern) {
            $rows += [pscustomobject] @{
                Serial = $Matches["serial"]
                State = $Matches["state"].ToLowerInvariant()
            }
        }
    }
    return $rows
}

function Get-ConnectedDeviceState {
    param([Parameter(Mandatory = $true)][string] $Adb)

    if (-not [IO.File]::Exists($Adb)) {
        throw "Android SDK adb.exe is missing"
    }
    $result = Invoke-CapturedNative -FilePath $Adb -ArgumentList @("devices", "-l")
    if ($result.ExitCode -ne 0) {
        throw "adb devices failed"
    }
    $deviceRows = @(Parse-AdbDeviceRows -Output $result.Output)
    if ($deviceRows.Count -eq 0) {
        return [pscustomobject] @{
            HasDevice = $false
            Status = "SKIPPED_NO_DEVICE"
            Serial = $null
            ApiLevel = $null
        }
    }

    $onlineSerials = @(
        $deviceRows |
            Where-Object { $_.State -ceq "device" } |
            ForEach-Object { $_.Serial }
    )
    if ($onlineSerials.Count -eq 0) {
        return [pscustomobject] @{
            HasDevice = $false
            Status = "SKIPPED_NO_USABLE_DEVICE"
            Serial = $null
            ApiLevel = $null
        }
    }

    $resolvedDevices = @()
    foreach ($serial in $onlineSerials) {
        $sdk = Invoke-CapturedNative -FilePath $Adb -ArgumentList @(
            "-s",
            $serial,
            "shell",
            "getprop",
            "ro.build.version.sdk"
        )
        [int] $apiLevel = 0
        if ($sdk.ExitCode -eq 0 -and [int]::TryParse($sdk.Output.Trim(), [ref] $apiLevel)) {
            $resolvedDevices += [pscustomobject] @{
                Serial = $serial
                ApiLevel = $apiLevel
            }
        }
    }
    if ($resolvedDevices.Count -eq 0) {
        return [pscustomobject] @{
            HasDevice = $false
            Status = "SKIPPED_NO_USABLE_DEVICE"
            Serial = $null
            ApiLevel = $null
        }
    }
    $api29Device = $resolvedDevices |
        Where-Object { $_.ApiLevel -ge 29 } |
        Select-Object -First 1
    if ($null -eq $api29Device) {
        return [pscustomobject] @{
            HasDevice = $false
            Status = "SKIPPED_NO_API29_DEVICE"
            Serial = $null
            ApiLevel = $null
        }
    }
    return [pscustomobject] @{
        HasDevice = $true
        Status = "PENDING"
        Serial = $api29Device.Serial
        ApiLevel = $api29Device.ApiLevel
    }
}

function Invoke-ConnectedTests {
    param(
        [Parameter(Mandatory = $true)][string] $CandidateVersion,
        [Parameter(Mandatory = $true)][int] $CandidateCode,
        [Parameter(Mandatory = $true)][string] $Serial
    )

    $arguments = @(
        "-PsoimVersionName=$CandidateVersion",
        "-PsoimVersionCode=$CandidateCode",
        ":app:connectedDebugAndroidTest",
        "--stacktrace"
    )
    $hadAndroidSerial = Test-Path Env:ANDROID_SERIAL
    $previousAndroidSerial = $env:ANDROID_SERIAL
    $env:ANDROID_SERIAL = $Serial
    Push-Location $Root
    try {
        & (Join-Path $Root "gradlew.bat") @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "connectedDebugAndroidTest failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
        if ($hadAndroidSerial) {
            $env:ANDROID_SERIAL = $previousAndroidSerial
        }
        else {
            Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
        }
    }
}

function Write-AtomicBytes {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][byte[]] $Bytes
    )

    $directory = [IO.Path]::GetDirectoryName($Path)
    $temporary = Join-Path $directory (".soim-publish-" + [IO.Path]::GetRandomFileName())
    $backup = Join-Path $directory (".soim-publish-backup-" + [IO.Path]::GetRandomFileName())
    $cleanupFailures = New-Object System.Collections.Generic.List[string]
    try {
        [IO.File]::WriteAllBytes($temporary, $Bytes)
        if ([IO.File]::Exists($Path)) {
            [IO.File]::Replace($temporary, $Path, $backup)
        }
        else {
            Move-Item -LiteralPath $temporary -Destination $Path
        }
    }
    finally {
        try {
            if ([IO.File]::Exists($temporary)) {
                Remove-Item -LiteralPath $temporary -Force -ErrorAction Stop
            }
        }
        catch {
            $cleanupFailures.Add("${temporary}: $($_.Exception.Message)")
        }
        try {
            if ([IO.File]::Exists($backup)) {
                Remove-Item -LiteralPath $backup -Force -ErrorAction Stop
            }
        }
        catch {
            $cleanupFailures.Add("${backup}: $($_.Exception.Message)")
        }
        if ($cleanupFailures.Count -gt 0) {
            Write-Warning ("Atomic file cleanup failed: " + ($cleanupFailures -join "; "))
        }
    }
}

function Get-PublicationSnapshot {
    param([Parameter(Mandatory = $true)][string[]] $Paths)

    $snapshot = @{}
    foreach ($path in $Paths) {
        $exists = [IO.File]::Exists($path)
        $snapshot[$path] = [pscustomobject] @{
            Exists = $exists
            BytesBase64 = if ($exists) {
                [Convert]::ToBase64String([IO.File]::ReadAllBytes($path))
            }
            else { "" }
        }
    }
    return $snapshot
}

function Restore-PublicationMetadata {
    param([Parameter(Mandatory = $true)][hashtable] $Snapshot)

    $rollbackFailures = New-Object System.Collections.Generic.List[string]
    foreach ($path in $Snapshot.Keys) {
        try {
            $state = $Snapshot[$path]
            $currentExists = [IO.File]::Exists($path)
            $currentBase64 = if ($currentExists) {
                [Convert]::ToBase64String([IO.File]::ReadAllBytes($path))
            }
            else { "" }
            if (
                $currentExists -eq $state.Exists -and
                (-not $currentExists -or $currentBase64 -ceq $state.BytesBase64)
            ) {
                continue
            }
            if ($state.Exists) {
                Write-AtomicBytes `
                    -Path $path `
                    -Bytes ([Convert]::FromBase64String($state.BytesBase64))
            }
            elseif ([IO.File]::Exists($path)) {
                Remove-Item -LiteralPath $path -Force -ErrorAction Stop
            }
        }
        catch {
            $rollbackFailures.Add("${path}: $($_.Exception.Message)")
        }
    }
    if ($rollbackFailures.Count -gt 0) {
        throw (
            "Publication rollback failed for $($rollbackFailures.Count) target(s): " +
                ($rollbackFailures -join "; ")
        )
    }
}

function Report-AtomicArtifacts {
    try {
        $leftovers = @(
            Get-ChildItem `
                -LiteralPath @($Root, $ApkDirectory.FullName) `
                -Filter ".soim-publish-*" `
                -File `
                -ErrorAction Stop
        )
    }
    catch {
        Write-Warning "Atomic artifact inspection failed: $($_.Exception.Message)"
        return
    }
    if ($leftovers.Count -gt 0) {
        Write-Warning (
            "Atomic publication artifacts remain: " +
                (($leftovers | ForEach-Object { $_.FullName }) -join "; ")
        )
    }
}

function Enter-PublisherLock {
    $lockPath = Join-Path $ApkDirectory.FullName ".soim-publisher.lock"
    try {
        return [IO.File]::Open(
            $lockPath,
            [IO.FileMode]::OpenOrCreate,
            [IO.FileAccess]::ReadWrite,
            [IO.FileShare]::None
        )
    }
    catch {
        throw "Another APK publisher holds the exclusive lock: $($_.Exception.Message)"
    }
}

function Exit-PublisherLock {
    param([Parameter(Mandatory = $true)][IO.FileStream] $Lock)

    $lockPath = $Lock.Name
    try {
        $Lock.Dispose()
    }
    catch {
        Write-Warning "Publisher lock dispose failed: $($_.Exception.Message)"
    }
    try {
        if ([IO.File]::Exists($lockPath)) {
            Remove-Item -LiteralPath $lockPath -Force -ErrorAction Stop
        }
    }
    catch {
        Write-Warning "Publisher lock cleanup failed: $($_.Exception.Message)"
    }
}

function Test-SnapshotUnchanged {
    param([Parameter(Mandatory = $true)][hashtable] $Snapshot)

    foreach ($path in $Snapshot.Keys) {
        $state = $Snapshot[$path]
        if ([IO.File]::Exists($path) -ne $state.Exists) {
            throw "Publication target changed before metadata commit: $path"
        }
        if ($state.Exists) {
            $current = [IO.File]::ReadAllBytes($path)
            if ([Convert]::ToBase64String($current) -cne $state.BytesBase64) {
                throw "Publication target changed before metadata commit: $path"
            }
        }
    }
}

function New-ChangelogEntry {
    param(
        [Parameter(Mandatory = $true)][string] $CandidateVersion,
        [Parameter(Mandatory = $true)][string] $NotesText,
        [Parameter(Mandatory = $true)][string] $ConnectedTestStatus,
        [Parameter(Mandatory = $true)][string] $Sha256
    )

    $passedBase64 = "LSDorr7lpIfmtYvor5XvvJpjb25uZWN0ZWREZWJ1Z0FuZHJvaWRUZXN0IOmAmui/hw=="
    $skippedBase64 = "LSDorr7lpIfmtYvor5XvvJrml6Dlj6/nlKjorr7lpIfmiJYgQVZE77yM5bey6Lez6L+H77yIezB977yJ"
    $templateBase64 = "IyMgdnswfSAoezF9KQoKIyMjIOWKn+iDvQp7Mn0KCiMjIyDkv67lpI0KLSDmlrDlop7ljp/lrZAgRGVidWcg5rWL6K+VIEFQSyDlj5HluIPmtYHnqIvvvIzlpLHotKXml7blm57mu5rniYjmnKzlhYPmlbDmja7kuI7liLblk4HjgIIKCiMjIyDmtYvor5UKLSBjbGVhbuOAgeWFqOmHj+WNleWFg+a1i+ivleOAgWxpbnREZWJ1Z+OAgUFuZHJvaWRUZXN0IOe8luivkeOAgURlYnVnL1JlbGVhc2Ug5p6E5bu65Z2H6YCa6L+H44CCCnszfQoKIyMjIOetvuWQjeS4juagoemqjAotIERlYnVnIOetvuWQje+8mkNOPUFuZHJvaWQgRGVidWcKLSBTSEEtMjU277yaezR9CgotLS0K"
    $decode = {
        param([string] $Value)
        return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value))
    }
    $connectedLine = if ($ConnectedTestStatus -eq "PASSED") {
        & $decode $passedBase64
    }
    else {
        (& $decode $skippedBase64) -f $ConnectedTestStatus
    }
    $template = & $decode $templateBase64
    return $template -f $CandidateVersion, $ReleaseDate, $NotesText, $connectedLine, $Sha256
}

function Commit-PublicationMetadata {
    param(
        [Parameter(Mandatory = $true)][string] $CandidateVersion,
        [Parameter(Mandatory = $true)][int] $CandidateCode,
        [Parameter(Mandatory = $true)][string] $CurrentMarkerPath,
        [Parameter(Mandatory = $true)][string] $CandidateMarkerPath,
        [Parameter(Mandatory = $true)][string] $SourceApkPath,
        [Parameter(Mandatory = $true)][string] $PublishedApkPath,
        [Parameter(Mandatory = $true)][string] $NotesText,
        [Parameter(Mandatory = $true)][string] $ConnectedTestStatus,
        [Parameter(Mandatory = $true)][string] $Sha256,
        [Parameter(Mandatory = $true)][byte[]] $LegacyChangelogBytes,
        [Parameter(Mandatory = $true)][hashtable] $OriginalSnapshot
    )

    if (
        [Convert]::ToBase64String($LegacyChangelogBytes) -cne
            $OriginalSnapshot[$ChangelogFile].BytesBase64
    ) {
        throw "Legacy changelog bytes do not match the original snapshot"
    }
    try {
        Write-AtomicBytes -Path $PublishedApkPath -Bytes ([IO.File]::ReadAllBytes($SourceApkPath))

        $versionText = "SOIM_VERSION_NAME=$CandidateVersion`nSOIM_VERSION_CODE=$CandidateCode`n"
        Write-AtomicBytes -Path $VersionFile -Bytes $script:Utf8NoBom.GetBytes($versionText)

        $entry = New-ChangelogEntry `
            -CandidateVersion $CandidateVersion `
            -NotesText $NotesText `
            -ConnectedTestStatus $ConnectedTestStatus `
            -Sha256 $Sha256
        $entryBytes = $script:Utf8NoBom.GetBytes($entry)
        $combined = New-Object byte[] ($entryBytes.Length + $LegacyChangelogBytes.Length)
        [Buffer]::BlockCopy($entryBytes, 0, $combined, 0, $entryBytes.Length)
        [Buffer]::BlockCopy(
            $LegacyChangelogBytes,
            0,
            $combined,
            $entryBytes.Length,
            $LegacyChangelogBytes.Length
        )
        Write-AtomicBytes -Path $ChangelogFile -Bytes $combined

        Move-Item -LiteralPath $CurrentMarkerPath -Destination $CandidateMarkerPath

        $markerFiles = @($ApkDirectory.GetFiles("current_version_is_*"))
        if ($markerFiles.Count -ne 1 -or $markerFiles[0].FullName -cne $CandidateMarkerPath) {
            throw "Publication did not leave exactly one candidate marker"
        }
        if ((Get-ApkSha256 -Path $PublishedApkPath) -cne $Sha256) {
            throw "Published APK SHA-256 does not match the verified build"
        }
        $versionBytes = [IO.File]::ReadAllBytes($VersionFile)
        $changelogBytes = [IO.File]::ReadAllBytes($ChangelogFile)
        foreach ($bytes in @($versionBytes, $changelogBytes)) {
            if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
                throw "Publication metadata must be BOM-free UTF-8"
            }
        }
        $legacyOffset = $changelogBytes.Length - $LegacyChangelogBytes.Length
        $legacySuffix = New-Object byte[] $LegacyChangelogBytes.Length
        [Buffer]::BlockCopy($changelogBytes, $legacyOffset, $legacySuffix, 0, $LegacyChangelogBytes.Length)
        if ([Convert]::ToBase64String($legacySuffix) -cne [Convert]::ToBase64String($LegacyChangelogBytes)) {
            throw "Legacy changelog bytes were not preserved"
        }
        Report-AtomicArtifacts
    }
    catch {
        $primaryFailure = $_
        try {
            Restore-PublicationMetadata -Snapshot $OriginalSnapshot
            Report-AtomicArtifacts
        }
        catch {
            $rollbackFailure = $_
            throw (
                "Primary failure: $($primaryFailure.Exception.Message)`n" +
                    "Rollback failure: $($rollbackFailure.Exception.Message)"
            )
        }
        throw $primaryFailure
    }
}

$CommittedProperties = Get-CommittedVersionProperties
$CurrentVersion = [string] $CommittedProperties["SOIM_VERSION_NAME"]
$rawCode = [string] $CommittedProperties["SOIM_VERSION_CODE"]
[int] $CurrentCode = 0
if (-not [int]::TryParse($rawCode, [ref] $CurrentCode) -or $CurrentCode -le 0) {
    throw "SOIM_VERSION_CODE must be a positive integer"
}
if ($CurrentCode -eq [int]::MaxValue) {
    throw "SOIM_VERSION_CODE cannot be incremented"
}

$CandidateVersion = Get-CandidateVersion -CurrentVersion $CurrentVersion -BumpKind $Bump
$CandidateCode = $CurrentCode + 1

Write-Output "SOIM_VERSION_NAME=$CandidateVersion"
Write-Output "SOIM_VERSION_CODE=$CandidateCode"

if ($WhatIfPreference) {
    Write-Output "WHAT_IF=NO_WRITES_NO_BUILD"
    return
}

if ([string]::IsNullOrWhiteSpace($Notes)) {
    throw "-Notes is required for an actual publication"
}

$CurrentMarkerPath = Join-Path $ApkDirectory.FullName "current_version_is_$CurrentVersion"
$CandidateMarker = Join-Path $ApkDirectory.FullName "current_version_is_$CandidateVersion"
$PublishedApk = Join-Path $ApkDirectory.FullName "soim-v$CandidateVersion-debug.apk"
$preBuildSnapshot = Get-PublicationSnapshot -Paths @(
    $VersionFile,
    $ChangelogFile,
    $CurrentMarkerPath,
    $CandidateMarker,
    $PublishedApk
)

Assert-PublicationTargetsClean -RelativePaths @(
    "version.properties",
    "apk/ver_change_log.md",
    "apk/current_version_is_$CurrentVersion"
)
$CurrentMarker = Get-CurrentMarker -Directory $ApkDirectory -ExpectedVersion $CurrentVersion
if ([IO.File]::Exists($CandidateMarker)) {
    throw "Candidate marker already exists: $CandidateMarker"
}
if ([IO.File]::Exists($PublishedApk)) {
    throw "Candidate APK already exists: $PublishedApk"
}

$NotesText = Resolve-NotesFile -Path $Notes
$LegacyChangelogBytes = [Convert]::FromBase64String(
    $preBuildSnapshot[$ChangelogFile].BytesBase64
)

$SdkDirectory = Get-SdkDirectory
$AndroidTools = Get-AndroidTools -SdkDirectory $SdkDirectory
Write-Output "BUILD_TOOLS=$($AndroidTools.BuildToolsVersion)"

Invoke-GradleGate -CandidateVersion $CandidateVersion -CandidateCode $CandidateCode

$DebugApk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
$ReleaseApk = Join-Path $Root "app\build\outputs\apk\release\app-release-unsigned.apk"
foreach ($apk in @($DebugApk, $ReleaseApk)) {
    if (-not [IO.File]::Exists($apk)) {
        throw "Expected build artifact is missing: $apk"
    }
}

$deviceState = Get-ConnectedDeviceState -Adb $AndroidTools.Adb
if ($deviceState.HasDevice) {
    Invoke-ConnectedTests `
        -CandidateVersion $CandidateVersion `
        -CandidateCode $CandidateCode `
        -Serial $deviceState.Serial
    $ConnectedTestStatus = "PASSED"
}
else {
    $ConnectedTestStatus = $deviceState.Status
}
Write-Output "CONNECTED_TESTS=$ConnectedTestStatus"

[void] (Test-ApkMetadata `
    -Aapt $AndroidTools.Aapt `
    -ApkPath $DebugApk `
    -ExpectedVersion $CandidateVersion `
    -ExpectedCode $CandidateCode)
[void] (Test-DebugSignature -ApkSigner $AndroidTools.ApkSigner -ApkPath $DebugApk)
Test-ReleaseUnsigned `
    -Aapt $AndroidTools.Aapt `
    -ApkSigner $AndroidTools.ApkSigner `
    -ApkPath $ReleaseApk `
    -ExpectedVersion $CandidateVersion `
    -ExpectedCode $CandidateCode
Test-ApkSecurity -Aapt $AndroidTools.Aapt -ApkPath $DebugApk
Test-LatestRoomSchema

$Sha256 = Get-ApkSha256 -Path $DebugApk
Write-Output "SHA256=$Sha256"

if (-not $PSCmdlet.ShouldProcess($Root, "Publish SoIM $CandidateVersion Debug test APK")) {
    return
}

$publisherLock = Enter-PublisherLock
try {
    Test-SnapshotUnchanged -Snapshot $preBuildSnapshot
    Report-AtomicArtifacts
    Commit-PublicationMetadata `
        -CandidateVersion $CandidateVersion `
        -CandidateCode $CandidateCode `
        -CurrentMarkerPath $CurrentMarker.FullName `
        -CandidateMarkerPath $CandidateMarker `
        -SourceApkPath $DebugApk `
        -PublishedApkPath $PublishedApk `
        -NotesText $NotesText `
        -ConnectedTestStatus $ConnectedTestStatus `
        -Sha256 $Sha256 `
        -LegacyChangelogBytes $LegacyChangelogBytes `
        -OriginalSnapshot $preBuildSnapshot
}
finally {
    Exit-PublisherLock -Lock $publisherLock
}

Write-Output "PUBLISHED_APK=$PublishedApk"
Write-Output "PUBLISHED_VERSION=$CandidateVersion"
