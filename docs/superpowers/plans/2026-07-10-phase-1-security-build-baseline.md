# Phase 1 Security and Build Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the demo entry path with a testable four-destination Compose shell, remove every packaged shared-secret and WebView/model demo path, establish the Android 10/release/network baseline, and export a clean Room version-1 schema.

**Architecture:** Phase 1 deliberately contains no MediaStore indexing, provider protocol, model request, WorkManager queue, or search implementation. `MainActivity` hosts a pure Compose application shell; architecture tests prevent the deleted H5, fixed-provider, secret-injection, and FTS5 code from returning. A real `AppSettingEntity` anchors the new Room schema because settings are part of the approved logical model; Phase 2 adds image and canonical-analysis tables through an explicit migration.

**Tech Stack:** Kotlin 2.0.21, Java 17, Android Gradle Plugin 8.7.3, minSdk 29, targetSdk/compileSdk 36, Jetpack Compose Material 3, Navigation Compose, Room 2.6.1 with KSP, JUnit 4, AndroidX Test, Compose UI Test.

## Global Constraints

- Work only on branch `codex/rebuild-core`; preserve unrelated edits in `docs/项目总览.md`, `h5/pnpm-lock.yaml`, `h5/pnpm-workspace.yaml`, `.superpowers/`, and `.kotlin/`.
- Do not modify the `h5/` source project. Remove only `app/src/main/assets/h5/` and Android WebView/JSBridge product code.
- Set `minSdk = 29`; keep `targetSdk = 36`, `compileSdk = 36`, Java 17, and Kotlin 2.0.21.
- The APK must contain no shared API key, fixed provider URL/model, raw-body logger, WebView product route, or legacy FTS5 callback.
- The release variant must never use the debug keystore. An unsigned release build is acceptable until the owner supplies real release signing.
- The manifest may permit cleartext for future user-approved LAN endpoints. Phase 1 contains no HTTP client; application-layer endpoint policy belongs to Phase 4.
- Disable Android system backup. The product uses its encrypted export/restore flow in Phase 8.
- Existing demo data is disposable. Do not migrate or reuse the old database schema.
- Use `apply_patch` for manual file edits/deletions. Generated Room schema JSON comes from KSP.
- Phase completion requires one connected emulator or physical device at API 29 or newer; build-only verification is insufficient.
- Every task ends with focused tests and a commit before the next task begins.

## Planned File Map

- Build/policy: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `.gitignore`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, new `app/src/main/res/xml/network_security_config.xml`, and new `docs/security/legacy-credential-rotation.md`.
- Compose shell: new `ui/app/AppDestination.kt`, `ui/app/SoImageManagerApp.kt`, `ui/components/FoundationScreen.kt`, four screen files, and updates to `MainActivity.kt`, theme files, and strings.
- Database: replacement `data/db/AppDatabase.kt`, new `AppDatabaseFactory.kt`, `AppSettingEntity.kt`, `AppSettingDao.kt`, and generated schema `app/schemas/cn.soul2.imageai.data.db.AppDatabase/1.json`.
- Tests: architecture/schema tests under `app/src/test`, Compose/device and Room tests under `app/src/androidTest`.
- Deletions: tracked `env/apikey.txt`, packaged `app/src/main/assets/h5/`, and legacy WebView, API, service, UI, DAO, and entity code. The top-level `h5/` source project is untouched.

---

### Task 1: Android 10 and Test Infrastructure

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/cn/soul2/imageai/architecture/PlatformContractTest.kt`

**Interfaces:**
- Produces: Android platform contract (`minSdk=29`, `targetSdk=36`, `compileSdk=36`) and Room schema/test source-set configuration.
- Produces: JUnit, AndroidX runner, Espresso, Compose UI test, and Room test dependencies.

- [ ] **Step 1: Add test dependencies and write the failing platform contract test**

Add catalog versions/libraries:

```toml
junit = "4.13.2"
androidxTestExtJunit = "1.2.1"
espressoCore = "3.6.1"

junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }
androidx-test-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
```

Create:

```kotlin
package cn.soul2.imageai.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformContractTest {
    @Test
    fun androidAndSchemaExportBaselineIsPinned() {
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val buildFile = File(root, "app/build.gradle.kts").readText()
        assertTrue(buildFile.contains("compileSdk = 36"))
        assertTrue(buildFile.contains("minSdk = 29"))
        assertTrue(buildFile.contains("targetSdk = 36"))
        assertTrue(buildFile.contains("room.schemaLocation"))
    }
}
```

- [ ] **Step 2: Run the test and verify the old floor fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests cn.soul2.imageai.architecture.PlatformContractTest`

Expected: FAIL because the build still uses minSdk 26 and has no schema location.

- [ ] **Step 3: Apply platform, schema, and test configuration**

Set `minSdk = 29`, then add:

```kotlin
ksp {
    arg("room.schemaLocation", file("$projectDir/schemas").path)
    arg("room.incremental", "true")
}

android {
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    testOptions { animationsDisabled = true }
}
```

Add dependencies:

```kotlin
testImplementation(libs.junit)
androidTestImplementation(libs.androidx.test.ext.junit)
androidTestImplementation(libs.androidx.test.espresso.core)
androidTestImplementation(platform(libs.androidx.compose.bom))
androidTestImplementation(libs.androidx.compose.ui.test.junit4)
androidTestImplementation(libs.room.testing)
```

- [ ] **Step 4: Run focused verification**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cn.soul2.imageai.architecture.PlatformContractTest
.\gradlew.bat :app:assembleDebug
```

Expected: both exit 0.

- [ ] **Step 5: Commit**

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/test/java/cn/soul2/imageai/architecture/PlatformContractTest.kt
git commit -m "build: establish Android 10 test baseline"
```

### Task 2: Pure Compose Application Shell

**Files:**
- Create: `app/src/main/java/cn/soul2/imageai/ui/app/AppDestination.kt`
- Create: `app/src/main/java/cn/soul2/imageai/ui/app/SoImageManagerApp.kt`
- Create: `app/src/main/java/cn/soul2/imageai/ui/components/FoundationScreen.kt`
- Create: `HomeScreen.kt`, `LibraryScreen.kt`, and `TasksScreen.kt` under `ui/screens/`
- Replace: `app/src/main/java/cn/soul2/imageai/ui/screens/SettingsScreen.kt`
- Modify: `MainActivity.kt`, `ui/theme/Theme.kt`, `ui/theme/Color.kt`, `ui/theme/Typography.kt`, and `res/values/strings.xml`
- Create: `app/src/test/java/cn/soul2/imageai/ui/app/AppDestinationTest.kt`
- Create: `app/src/androidTest/java/cn/soul2/imageai/ui/app/AppShellTest.kt`

**Interfaces:**
- Produces: `AppDestination.entries` ordered Home, Library, Tasks, Settings and `AppDestination.start = HOME`.
- Produces: `SoImageManagerApp(navController: NavHostController)`.
- Produces: semantic tags `destination_<route>` and `screen_<route>`.

- [ ] **Step 1: Write the failing destination test**

```kotlin
package cn.soul2.imageai.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppDestinationTest {
    @Test
    fun destinationsAreStableUniqueAndHomeFirst() {
        assertEquals(listOf("home", "library", "tasks", "settings"), AppDestination.entries.map { it.route })
        assertEquals(AppDestination.entries.size, AppDestination.entries.map { it.route }.toSet().size)
        assertSame(AppDestination.HOME, AppDestination.start)
    }
}
```

- [ ] **Step 2: Verify the missing contract**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests cn.soul2.imageai.ui.app.AppDestinationTest`

Expected: FAIL because `AppDestination` is absent.

- [ ] **Step 3: Implement destination and navigation contracts**

```kotlin
package cn.soul2.imageai.ui.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import cn.soul2.imageai.R

enum class AppDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Outlined.Home),
    LIBRARY("library", R.string.nav_library, Icons.Outlined.PhotoLibrary),
    TASKS("tasks", R.string.nav_tasks, Icons.Outlined.Checklist),
    SETTINGS("settings", R.string.nav_settings, Icons.Outlined.Settings);

    companion object { val start: AppDestination = HOME }
}
```

Create `SoImageManagerApp.kt`:

```kotlin
package cn.soul2.imageai.ui.app

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.soul2.imageai.ui.screens.HomeScreen
import cn.soul2.imageai.ui.screens.LibraryScreen
import cn.soul2.imageai.ui.screens.SettingsScreen
import cn.soul2.imageai.ui.screens.TasksScreen

@Composable
fun SoImageManagerApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.start.route

    Scaffold(
        bottomBar = {
            NavigationBar(Modifier.testTag("bottom_navigation")) {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(AppDestination.start.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                        modifier = Modifier.testTag("destination_${destination.route}"),
                    )
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.start.route,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(AppDestination.HOME.route) { HomeScreen() }
            composable(AppDestination.LIBRARY.route) { LibraryScreen() }
            composable(AppDestination.TASKS.route) { TasksScreen() }
            composable(AppDestination.SETTINGS.route) { SettingsScreen() }
        }
    }
}
```

Do not add a second navigation abstraction.

- [ ] **Step 4: Implement restrained screens and theme cleanup**

```kotlin
package cn.soul2.imageai.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoundationScreen(@StringRes titleRes: Int, @StringRes emptyLabelRes: Int, testTag: String) {
    Column(Modifier.fillMaxSize().testTag(testTag)) {
        TopAppBar(title = { Text(stringResource(titleRes)) })
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(emptyLabelRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
```

Create the four screen files with these exact calls:

```kotlin
package cn.soul2.imageai.ui.screens

import androidx.compose.runtime.Composable
import cn.soul2.imageai.R
import cn.soul2.imageai.ui.components.FoundationScreen

@Composable
fun HomeScreen() = FoundationScreen(R.string.nav_home, R.string.home_empty, "screen_home")
```

```kotlin
package cn.soul2.imageai.ui.screens

import androidx.compose.runtime.Composable
import cn.soul2.imageai.R
import cn.soul2.imageai.ui.components.FoundationScreen

@Composable
fun LibraryScreen() = FoundationScreen(R.string.nav_library, R.string.library_empty, "screen_library")
```

```kotlin
package cn.soul2.imageai.ui.screens

import androidx.compose.runtime.Composable
import cn.soul2.imageai.R
import cn.soul2.imageai.ui.components.FoundationScreen

@Composable
fun TasksScreen() = FoundationScreen(R.string.nav_tasks, R.string.tasks_empty, "screen_tasks")
```

```kotlin
package cn.soul2.imageai.ui.screens

import androidx.compose.runtime.Composable
import cn.soul2.imageai.R
import cn.soul2.imageai.ui.components.FoundationScreen

@Composable
fun SettingsScreen() = FoundationScreen(R.string.nav_settings, R.string.settings_empty, "screen_settings")
```

Replace `strings.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">So Image Manager</string>
    <string name="nav_home">Home</string>
    <string name="nav_library">Library</string>
    <string name="nav_tasks">Tasks</string>
    <string name="nav_settings">Settings</string>
    <string name="home_empty">No indexed images</string>
    <string name="library_empty">No library items</string>
    <string name="tasks_empty">No active tasks</string>
    <string name="settings_empty">No settings configured</string>
</resources>
```

Replace theme DataStore lookup with `isSystemInDarkTheme()` and rename the composable `SoImageManagerTheme`:

```kotlin
@Composable
fun SoImageManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
```

Set `LightPrimary = Color(0xFF256B5A)`, `DarkPrimary = Color(0xFF77C8B1)`, and `DarkOnPrimary = Color(0xFF082019)`. Keep the existing neutral background/surface tokens, delete `Purple80`, `PurpleGrey80`, `Pink80`, `Purple40`, `PurpleGrey40`, and `Pink40`, and set every typography `letterSpacing` to `0.sp`. Do not add gradients.

Update `MainActivity`:

```kotlin
setContent {
    SoImageManagerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SoImageManagerApp()
        }
    }
}
```

- [ ] **Step 5: Add the device navigation test**

```kotlin
package cn.soul2.imageai.ui.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.soul2.imageai.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun allPrimaryDestinationsNavigateAndRestore() {
        composeRule.onNodeWithTag("screen_home").assertIsDisplayed()
        listOf("library", "tasks", "settings").forEach { route ->
            composeRule.onNodeWithTag("destination_$route").performClick()
            composeRule.onNodeWithTag("screen_$route").assertIsDisplayed()
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
    }
}
```

- [ ] **Step 6: Run tests and build**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cn.soul2.imageai.ui.app.AppDestinationTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected: unit/build exit 0; API 29+ device test opens all destinations and restores Settings.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/cn/soul2/imageai/MainActivity.kt app/src/main/java/cn/soul2/imageai/ui app/src/main/res/values/strings.xml app/src/test app/src/androidTest
git commit -m "feat: add Compose application shell"
```

### Task 3: Remove Shared Secrets and Legacy Demo Paths

**Files:**
- Create: `app/src/test/java/cn/soul2/imageai/architecture/LegacySurfaceTest.kt`
- Modify: `.gitignore`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, and `app/proguard-rules.pro`
- Create: `docs/security/legacy-credential-rotation.md`
- Delete: `env/apikey.txt` and `app/src/main/assets/h5/`
- Delete: `AppNavigation.kt`, `webview/`, `data/api/`, `domain/service/`, old `data/db/`, `ui/navigation/`, and `ui/settings/`
- Delete: old `GalleryScreen.kt`, `ImageDetailScreen.kt`, and `WebViewScreen.kt`

**Interfaces:**
- Produces: no packaged class capable of fixed-provider requests or H5 loading.
- Produces: permanent `LegacySurfaceTest` guard.

- [ ] **Step 1: Write the failing architecture guard**

```kotlin
package cn.soul2.imageai.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacySurfaceTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun secretAndDemoProductPathsAreAbsent() {
        listOf(
            "env/apikey.txt",
            "app/src/main/assets/h5",
            "app/src/main/java/cn/soul2/imageai/webview",
            "app/src/main/java/cn/soul2/imageai/data/api",
            "app/src/main/java/cn/soul2/imageai/domain/service",
        ).forEach { path -> assertFalse("Legacy path remains: $path", File(root, path).exists()) }

        val mainText = File(root, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "kts", "xml") }
            .joinToString("\n") { it.readText() }
        listOf("AI_API_KEY", "apikey.txt", "android.webkit.WebView", "WebViewAssetLoader", "fts5(")
            .forEach { token -> assertFalse("Forbidden product token remains: $token", mainText.contains(token)) }
    }
}
```

- [ ] **Step 2: Verify the guard detects current risk**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests cn.soul2.imageai.architecture.LegacySurfaceTest`

Expected: FAIL listing the secret, packaged H5, WebView, API, service, and FTS5 surfaces.

- [ ] **Step 3: Delete obsolete product code**

Delete every listed path with `apply_patch`. Delete all old DAO/entity files with the old `AppDatabase.kt`; Task 5 creates a new baseline. Do not edit or delete the top-level `h5/` project.

- [ ] **Step 4: Remove injection and unused dependencies**

Remove `java.io.File`, `AI_API_URL`, `AI_API_KEY`, `AI_MODEL`, and `buildConfig = true` from `app/build.gradle.kts`. Remove WebKit, OkHttp, logging interceptor, DataStore, Coil, and DocumentFile dependencies and catalog entries; retain Room, coroutines, Navigation Compose, and Material icons. Remove WebView/JSBridge ProGuard rules. Add `/env/` to `.gitignore`.

Create:

```markdown
# Legacy Credential Rotation

The rebuild no longer reads, compiles, packages, logs, or exports a shared model credential. The previously tracked credential must be revoked at its provider before any rebuilt APK is distributed because deleting it from the current tree does not remove it from Git history.

Revocation is an external owner action and cannot be inferred from repository state. Record only the provider, revocation date, and operator in the private release record; never record the credential value in this repository.
```

- [ ] **Step 5: Verify removal and build**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cn.soul2.imageai.architecture.LegacySurfaceTest
git ls-files -- env/apikey.txt app/src/main/assets/h5
git grep -n -E "AI_API_KEY|apikey.txt|aiApiKey|sunskii|WebViewAssetLoader|fts5" -- app env
.\gradlew.bat :app:assembleDebug
```

Expected: tests/build exit 0 and both Git searches print no tracked product match.

- [ ] **Step 6: Commit**

```powershell
git add -A .gitignore app env gradle/libs.versions.toml docs/security/legacy-credential-rotation.md
git commit -m "security: remove shared secret and demo product paths"
```

### Task 4: Release and Network Security Configuration

**Files:**
- Create: `app/src/test/java/cn/soul2/imageai/architecture/SecurityConfigurationTest.kt`
- Modify: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, and `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/xml/network_security_config.xml`

**Interfaces:**
- Produces: no debug-signed release variant.
- Produces: platform cleartext capability without an HTTP client or policy bypass.
- Produces: system-backup-disabled manifest.

- [ ] **Step 1: Write the failing security configuration test**

```kotlin
package cn.soul2.imageai.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityConfigurationTest {
    private val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun manifestAndReleasePolicyMatchFrozenBaseline() {
        val build = File(root, "app/build.gradle.kts").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val network = File(root, "app/src/main/res/xml/network_security_config.xml")
        assertFalse(build.contains("create(\"release\")"))
        assertFalse(build.contains("signingConfigs.getByName(\"release\")"))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"true\""))
        assertTrue(network.readText().contains("cleartextTrafficPermitted=\"true\""))
    }
}
```

- [ ] **Step 2: Verify current release/manifest policy fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests cn.soul2.imageai.architecture.SecurityConfigurationTest`

Expected: FAIL because release uses the debug key, backup is enabled, and XML is absent.

- [ ] **Step 3: Apply release and manifest policy**

Delete the custom release signing config and assignment. Keep release unsigned/non-debuggable:

```kotlin
release {
    isDebuggable = false
    isMinifyEnabled = false
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
    )
}
```

Set application attributes:

```xml
android:allowBackup="false"
android:fullBackupContent="false"
android:networkSecurityConfig="@xml/network_security_config"
android:usesCleartextTraffic="true"
```

Create:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

Rename the platform theme to `Theme.SoImageManager`, use it from the manifest, and align system bars with Compose background/surface rather than the old primary color.

- [ ] **Step 4: Verify policy, signing, and variants**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests cn.soul2.imageai.architecture.SecurityConfigurationTest
.\gradlew.bat :app:signingReport
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```

Expected: test/builds exit 0; `signingReport` shows normal debug signing and no debug certificate for release. The release artifact remains undistributable until separately signed.

- [ ] **Step 5: Commit**

```powershell
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res/xml/network_security_config.xml app/src/main/res/values/themes.xml app/src/test/java/cn/soul2/imageai/architecture/SecurityConfigurationTest.kt
git commit -m "security: establish release and network policy"
```

### Task 5: Clean Room Version-1 Schema

**Files:**
- Create: `data/db/entity/AppSettingEntity.kt`, `data/db/dao/AppSettingDao.kt`, `data/db/AppDatabase.kt`, and `data/db/AppDatabaseFactory.kt`
- Create: `app/src/androidTest/java/cn/soul2/imageai/data/db/AppDatabaseTest.kt`
- Create: `app/src/test/java/cn/soul2/imageai/data/db/RoomSchemaContractTest.kt`
- Generate: `app/schemas/cn.soul2.imageai.data.db.AppDatabase/1.json`

**Interfaces:**
- Produces: `AppSettingDao.getByKey(key: String): AppSettingEntity?`, `upsert(AppSettingEntity)`, and `deleteByKey(String)`.
- Produces: `AppDatabaseFactory.create(context: Context): AppDatabase` using `so_image_manager.db`.
- Produces: version 1 with only `app_setting`; no old image, AI, cache, feature, tag, or FTS table.

- [ ] **Step 1: Write the failing database test**

```kotlin
package cn.soul2.imageai.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.soul2.imageai.data.db.entity.AppSettingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun closeDatabase() = database.close()

    @Test
    fun settingRoundTripsThroughVersionOneSchema() = runBlocking {
        val expected = AppSettingEntity("appearance.theme", "\"system\"", 1_720_598_400_000L)
        database.appSettingDao().upsert(expected)
        assertEquals(expected, database.appSettingDao().getByKey(expected.key))
        database.appSettingDao().deleteByKey(expected.key)
        assertNull(database.appSettingDao().getByKey(expected.key))
    }
}
```

Also create the schema guard:

```kotlin
package cn.soul2.imageai.data.db

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSchemaContractTest {
    @Test
    fun versionOneContainsOnlyTheNewBaselineTable() {
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val schema = File(
            root,
            "app/schemas/cn.soul2.imageai.data.db.AppDatabase/1.json",
        )
        assertTrue("Room schema must be exported", schema.isFile)
        val text = schema.readText()
        assertTrue(text.contains("app_setting"))
        listOf("image_fts", "fts5", "image_ai", "image_feature", "image_query_cache")
            .forEach { assertFalse("Legacy schema term remains: $it", text.contains(it)) }
    }
}
```

- [ ] **Step 2: Verify database types are missing**

Run:

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:testDebugUnitTest --tests cn.soul2.imageai.data.db.RoomSchemaContractTest
```

Expected: both fail because Task 3 removed the old database and no version-1 schema is exported.

- [ ] **Step 3: Implement entity, DAO, database, and factory**

```kotlin
package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "value_json") val valueJson: String,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long,
)
```

```kotlin
package cn.soul2.imageai.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.AppSettingEntity

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_setting WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): AppSettingEntity?

    @Upsert suspend fun upsert(setting: AppSettingEntity)

    @Query("DELETE FROM app_setting WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}
```

```kotlin
package cn.soul2.imageai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import cn.soul2.imageai.data.db.dao.AppSettingDao
import cn.soul2.imageai.data.db.entity.AppSettingEntity

@Database(entities = [AppSettingEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appSettingDao(): AppSettingDao
}
```

```kotlin
package cn.soul2.imageai.data.db

import android.content.Context
import androidx.room.Room

object AppDatabaseFactory {
    private const val DATABASE_NAME = "so_image_manager.db"

    fun create(context: Context): AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        DATABASE_NAME,
    ).build()
}
```

Use explicit imports. Do not add raw-SQL callbacks, destructive migration fallback, or FTS.

- [ ] **Step 4: Generate and inspect schema**

Run:

```powershell
.\gradlew.bat :app:kspDebugKotlin
rg -n "app_setting|image_fts|fts5|image_ai|image_feature" app/schemas/cn.soul2.imageai.data.db.AppDatabase/1.json
.\gradlew.bat :app:testDebugUnitTest --tests cn.soul2.imageai.data.db.RoomSchemaContractTest
```

Expected: `rg` prints the `app_setting` entry, the schema contract test passes, and no legacy term is present.

- [ ] **Step 5: Run database/regression tests**

Run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Expected: Room round-trip, Compose device test, architecture tests, and build pass.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/cn/soul2/imageai/data/db app/src/androidTest/java/cn/soul2/imageai/data/db app/src/test/java/cn/soul2/imageai/data/db app/schemas
git commit -m "feat: add clean Room schema baseline"
```

### Task 6: Phase 1 Release Gate

**Files:**
- Verify only; do not modify unrelated workspace files.

**Interfaces:**
- Produces: installable debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Produces: unsigned/non-debug release artifact at `app/build/outputs/apk/release/`.
- Produces: unit, lint, build, schema, and connected-device acceptance evidence.

- [ ] **Step 1: Run complete automated gate**

Run:

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL` with no test/lint failure.

- [ ] **Step 2: Run connected-device gate**

Run:

```powershell
adb shell getprop ro.build.version.sdk
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected: API is at least 29; Room round-trip and Compose navigation/recreation pass.

- [ ] **Step 3: Verify security boundaries**

Run:

```powershell
git ls-files -- env/apikey.txt app/src/main/assets/h5
git grep -n -E "AI_API_KEY|aiApiKey|sunskii|WebViewAssetLoader|android.webkit.WebView|fts5" -- app env
git diff --check
git status --short
```

Expected: security searches produce no tracked product match; diff check exits 0; status contains only known unrelated user changes.

- [ ] **Step 4: Inspect artifacts and external blocker**

Confirm the debug APK launches to Home, all four destinations are reachable, and no WebView/H5 screen exists. The previously tracked provider key remains a distribution blocker until the owner confirms revocation outside Git.

- [ ] **Step 5: Record phase completion**

If no correction was needed, do not create an empty commit. Record Task 1-5 commit hashes in the handoff and keep the branch ready for Phase 2 planning.
