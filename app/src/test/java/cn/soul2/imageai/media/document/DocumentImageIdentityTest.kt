package cn.soul2.imageai.media.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImageIdentityTest {
    @Test
    fun sameUriKeepsStableIdentityAcrossImports() {
        val uri = "content://com.android.providers.media.documents/document/image%3A42"

        assertEquals(DocumentImageIdentity.of(uri), DocumentImageIdentity.of(uri))
    }

    @Test
    fun differentUrisReceiveIndependentNonZeroIdentities() {
        val first = DocumentImageIdentity.of(
            "content://com.android.providers.media.documents/document/image%3A42",
        )
        val second = DocumentImageIdentity.of(
            "content://com.android.providers.media.documents/document/image%3A43",
        )

        assertNotEquals(first.mediaStoreId, second.mediaStoreId)
        assertTrue(first.mediaStoreId > 0L)
        assertTrue(second.mediaStoreId > 0L)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }
}
