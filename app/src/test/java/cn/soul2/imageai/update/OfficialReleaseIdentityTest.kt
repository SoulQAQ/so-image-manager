package cn.soul2.imageai.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialReleaseIdentityTest {
    @Test
    fun emptyCertificateIsExplicitlyUnconfigured() {
        val identity = OfficialReleaseIdentity.create("cn.soul2.imageai", "0.19.0", "v0.19.0", "")

        assertFalse(identity.isConfigured)
    }

    @Test
    fun configuredCertificateIsNormalizedAndValidated() {
        val identity = OfficialReleaseIdentity.create(
            "cn.soul2.imageai", "0.19.0", "v0.19.0", "AA:" + "bb".repeat(31),
        )

        assertTrue(identity.isConfigured)
        assertThrows(IllegalArgumentException::class.java) {
            OfficialReleaseIdentity.create("cn.soul2.imageai", "0.19.0", "v0.19.1", "a".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OfficialReleaseIdentity.create("cn.soul2.imageai", "0.19.0", "v0.19.0", "not-a-cert")
        }
    }
}
