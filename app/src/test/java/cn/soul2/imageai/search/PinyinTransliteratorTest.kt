package cn.soul2.imageai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinTransliteratorTest {
    @Test
    fun phraseDictionarySelectsPrimaryPolyphonicPronunciations() {
        assertEquals("chongqing", PinyinTransliterator.completeStreams("重庆").full)
        assertEquals("yinhang", PinyinTransliterator.completeStreams("银行").full)
        assertEquals("changan", PinyinTransliterator.completeStreams("长安").full)
    }

    @Test
    fun completeStreamsPassThroughNormalizedNonCjkText() {
        val streams = PinyinTransliterator.completeStreams("重庆  Ｐｈｏｔｏ---2026")

        assertEquals("chongqing photo 2026", streams.full)
        assertEquals("cq photo 2026", streams.initials)
    }

    @Test
    fun pinyinIsLowercaseToneFreeAndUsesVForUmlaut() {
        val aliases = PinyinTransliterator.lexicalAliases("女好")

        assertTrue(aliases.any { it.type == PinyinAliasType.FULL && it.text == "nvhao" })
        assertTrue(aliases.none { alias -> alias.text.any(Char::isDigit) || 'ü' in alias.text })
    }

    @Test
    fun lexicalExpansionHasAtMostTwoFullFormsAndOneInitialsForm() {
        listOf("重庆", "银行", "长安", "重行长").forEach { term ->
            val aliases = PinyinTransliterator.lexicalAliases(term)

            assertTrue(aliases.count { it.type == PinyinAliasType.FULL } <= 2)
            assertTrue(aliases.count { it.type == PinyinAliasType.INITIALS } <= 1)
            assertTrue(aliases.size <= SearchLimits.LEXICAL_ALIAS_COUNT)
            assertTrue(aliases.all { it.text.length <= SearchLimits.LEXICAL_ALIAS_CHARACTERS })
        }
    }

    @Test
    fun lexicalAliasesIncludePhrasePrimaryAlternatesAndInitials() {
        val aliases = PinyinTransliterator.lexicalAliases("重庆")

        assertEquals(PinyinAlias(PinyinAliasType.FULL, "chongqing"), aliases.first())
        assertTrue(aliases.any { it.type == PinyinAliasType.FULL && it.text == "zhongqing" })
        assertTrue(aliases.any { it.type == PinyinAliasType.INITIALS && it.text == "cq" })
    }

    @Test
    fun nonCjkLexicalTermsDoNotReceivePinyinAliases() {
        assertEquals(emptyList<PinyinAlias>(), PinyinTransliterator.lexicalAliases("Photo 2026"))
    }

    @Test
    fun completeCaptionStreamsAreNotTruncatedToLexicalAliasLength() {
        val source = "重庆".repeat(100)
        val streams = PinyinTransliterator.completeStreams(source)

        assertEquals(900, streams.full.length)
        assertEquals(200, streams.initials.length)
        assertTrue(streams.full.length > SearchLimits.LEXICAL_ALIAS_CHARACTERS)
    }

    @Test
    fun completeStreamsUseIndependent512CharacterOverlappingChunks() {
        val streams = PinyinTransliterator.completeStreams("重庆".repeat(100))

        assertEquals(listOf(0, 385, 770), streams.fullChunks.map { it.startOffset })
        assertEquals(listOf(512, 512, 130), streams.fullChunks.map { it.text.length })
        assertEquals(streams.full, reconstruct(streams.fullChunks))
        assertEquals(streams.initials, reconstruct(streams.initialsChunks))
    }

    private fun reconstruct(chunks: List<SearchTextChunk>): String = buildString {
        chunks.forEachIndexed { index, chunk ->
            if (index == 0) append(chunk.text) else append(chunk.text.drop(SearchLimits.ALIAS_CHUNK_OVERLAP))
        }
    }
}
