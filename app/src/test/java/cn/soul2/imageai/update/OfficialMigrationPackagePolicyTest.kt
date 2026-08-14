package cn.soul2.imageai.update

import org.junit.Assert.assertThrows
import org.junit.Test

class OfficialMigrationPackagePolicyTest {
    private val certificate = "a".repeat(64)
    private val identity = OfficialReleaseIdentity.create(
        "cn.soul2.imageai", "0.19.0", "v0.19.0", certificate,
    )
    private val release = UpdateRelease(
        SemanticVersion(0, 19, 0), "v0.19.0", "SoIM", "", "", "https://github.com/",
        UpdateAsset("soim-v0.19.0-release.apk", "https://github.com/a", 1L, "b".repeat(64)),
    )

    @Test
    fun acceptsOnlyPinnedPackageVersionAndSingleSigner() {
        OfficialMigrationPackagePolicy.validate(
            identity, release, "cn.soul2.imageai", "0.19.0", 31L, setOf(certificate),
        )

        assertThrows(AppUpdateException::class.java) {
            OfficialMigrationPackagePolicy.validate(identity, release, "other", "0.19.0", 31L, setOf(certificate))
        }
        assertThrows(AppUpdateException::class.java) {
            OfficialMigrationPackagePolicy.validate(identity, release, "cn.soul2.imageai", "0.19.1", 31L, setOf(certificate))
        }
        assertThrows(AppUpdateException::class.java) {
            OfficialMigrationPackagePolicy.validate(identity, release, "cn.soul2.imageai", "0.19.0", 31L, setOf("c".repeat(64)))
        }
        assertThrows(AppUpdateException::class.java) {
            OfficialMigrationPackagePolicy.validate(identity, release, "cn.soul2.imageai", "0.19.0", 31L, setOf(certificate, "c".repeat(64)))
        }
    }
}
