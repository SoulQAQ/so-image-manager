package cn.soul2.imageai.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchGramGeneratorTest {
    @Test
    fun cjkRunsEmitUniqueUnicodeBigrams() {
        assertEquals(linkedSetOf("重庆", "庆重"), SearchGramGenerator.grams("重庆重庆"))
    }

    @Test
    fun latinAndDigitRunsEmitUniqueTrigrams() {
        assertEquals(
            linkedSetOf("abc", "bc1", "c12", "123"),
            SearchGramGenerator.grams("ABC123"),
        )
    }

    @Test
    fun mixedScriptsEmitOnlyTheirRelevantGramForms() {
        assertEquals(
            linkedSetOf("北京", "abc", "bc1"),
            SearchGramGenerator.grams("北京abc1"),
        )
    }

    @Test
    fun separatorsCollapseAndPreventCrossUnitGrams() {
        assertEquals(
            linkedSetOf("foo", "bar"),
            SearchGramGenerator.grams("  ＦＯＯ---bar\u3000bar "),
        )
    }

    @Test
    fun inputsShorterThanTheScriptGramSizeEmitNothing() {
        listOf("", "重", "ab", "重 a").forEach { input ->
            assertEquals(emptySet<String>(), SearchGramGenerator.grams(input))
        }
    }

    @Test
    fun supplementaryHanCharactersAreCountedAsCodePoints() {
        val extensionB = "𠀀𠀁𠀀"

        assertEquals(linkedSetOf("𠀀𠀁", "𠀁𠀀"), SearchGramGenerator.grams(extensionB))
    }
}
