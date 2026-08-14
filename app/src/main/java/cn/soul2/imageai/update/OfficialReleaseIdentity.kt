package cn.soul2.imageai.update

import cn.soul2.imageai.BuildConfig

data class OfficialReleaseIdentity(
    val packageId: String,
    val firstVersion: SemanticVersion,
    val firstTag: String,
    val certificateSha256: String?,
) {
    val isConfigured: Boolean get() = certificateSha256 != null

    companion object {
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        fun create(
            packageId: String,
            firstVersion: String,
            firstTag: String,
            certificateSha256: String,
        ): OfficialReleaseIdentity {
            require(packageId.isNotBlank()) { "正式版包名不能为空" }
            val version = requireNotNull(SemanticVersion.parse(firstVersion)) { "正式版版本号无效" }
            require(firstTag == "v$version") { "正式版标签与版本不一致" }
            val normalizedCertificate = certificateSha256.trim().replace(":", "").lowercase()
            require(normalizedCertificate.isEmpty() || SHA256.matches(normalizedCertificate)) {
                "正式版证书 SHA-256 无效"
            }
            return OfficialReleaseIdentity(
                packageId = packageId,
                firstVersion = version,
                firstTag = firstTag,
                certificateSha256 = normalizedCertificate.ifEmpty { null },
            )
        }

        val Current: OfficialReleaseIdentity by lazy {
            create(
                packageId = BuildConfig.SOIM_OFFICIAL_PACKAGE_ID,
                firstVersion = BuildConfig.SOIM_FIRST_OFFICIAL_VERSION,
                firstTag = BuildConfig.SOIM_FIRST_OFFICIAL_TAG,
                certificateSha256 = BuildConfig.SOIM_OFFICIAL_CERT_SHA256,
            )
        }
    }
}
