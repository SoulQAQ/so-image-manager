package cn.soul2.imageai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredSearchParserTest {
    @Test
    fun parsesOnlyWhitelistedFiltersAndKeepsFreeText() {
        val result = StructuredSearchParser.parse("标签:旅行 分类：风景 相册:相机 海边 order:drop")

        assertEquals(listOf("旅行"), result.tags)
        assertEquals(listOf("风景"), result.categories)
        assertEquals(listOf("相机"), result.albums)
        assertEquals("海边 order:drop", result.freeText)
        assertTrue(result.hasFilters)
    }

    @Test
    fun convertsOnlyExplicitNaturalLanguageFilterPhrases() {
        val result = StructuredSearchParser.parse("标签是旅行 分类为风景 相册里的相机 海边夜景")

        assertEquals(listOf("旅行"), result.tags)
        assertEquals(listOf("风景"), result.categories)
        assertEquals(listOf("相机"), result.albums)
        assertEquals("海边夜景", result.freeText)
    }
}
