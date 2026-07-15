package cn.soul2.imageai.search

import java.text.Normalizer
import java.util.Locale
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

enum class PinyinAliasType {
    FULL,
    INITIALS,
}

data class PinyinAlias(
    val type: PinyinAliasType,
    val text: String,
)

data class PinyinStreams(
    val full: String,
    val initials: String,
    val fullChunks: List<SearchTextChunk>,
    val initialsChunks: List<SearchTextChunk>,
)

object PinyinTransliterator {
    private const val PHRASE_RESOURCE = "/cn/soul2/imageai/search/polyphonic_phrases.tsv"

    private val outputFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    private val phraseTrie: PhraseTrie by lazy {
        val stream = PinyinTransliterator::class.java.getResourceAsStream(PHRASE_RESOURCE)
            ?: error("Missing pinyin phrase resource: $PHRASE_RESOURCE")
        stream.bufferedReader(Charsets.UTF_8).use { reader -> PhraseTrie.load(reader.readLines()) }
    }

    fun lexicalAliases(term: String): List<PinyinAlias> {
        val transliteration = transliterate(term)
        if (!transliteration.hasPinyin) return emptyList()

        val fullForms = linkedSetOf(transliteration.full())
        outer@ for (index in transliteration.segments.indices) {
            val segment = transliteration.segments[index]
            for (alternative in segment.alternatives) {
                val full = transliteration.full(index, alternative)
                if (full.length <= SearchLimits.LEXICAL_ALIAS_CHARACTERS) fullForms += full
                if (fullForms.size == 2) break@outer
            }
        }

        return buildList {
            fullForms
                .filter { it.isNotEmpty() && it.length <= SearchLimits.LEXICAL_ALIAS_CHARACTERS }
                .take(2)
                .forEach { add(PinyinAlias(PinyinAliasType.FULL, it)) }
            val initials = transliteration.initials()
            if (initials.isNotEmpty() && initials.length <= SearchLimits.LEXICAL_ALIAS_CHARACTERS) {
                add(PinyinAlias(PinyinAliasType.INITIALS, initials))
            }
        }.take(SearchLimits.LEXICAL_ALIAS_COUNT)
    }

    fun completeStreams(text: String): PinyinStreams {
        val transliteration = transliterate(text)
        val full = transliteration.full()
        val initials = transliteration.initials()
        return PinyinStreams(
            full = full,
            initials = initials,
            fullChunks = OverlappingChunker.chunkByCharacters(full),
            initialsChunks = OverlappingChunker.chunkByCharacters(initials),
        )
    }

    private fun transliterate(raw: String): Transliteration {
        requireValidSearchUtf16(raw, "text")
        val text = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val segments = mutableListOf<Segment>()
        var hasPinyin = false
        var offset = 0
        while (offset < text.length) {
            val phrase = phraseTrie.longestMatch(text, offset)
            if (phrase != null) {
                phrase.codePoints.zip(phrase.syllables).forEach { (codePoint, primary) ->
                    segments += pinyinSegment(codePoint, primary)
                }
                hasPinyin = true
                offset = phrase.endOffset
                continue
            }

            val codePoint = text.codePointAt(offset)
            val pronunciations = pronunciations(codePoint)
            when {
                pronunciations.isNotEmpty() -> {
                    segments += Segment(
                        primary = pronunciations.first(),
                        initial = pronunciations.first().take(1),
                        alternatives = pronunciations.drop(1),
                    )
                    hasPinyin = true
                }
                Character.isLetterOrDigit(codePoint) -> {
                    val value = buildString { appendCodePoint(codePoint) }
                    segments += Segment(value, value)
                }
                else -> segments += Segment(" ", " ")
            }
            offset += Character.charCount(codePoint)
        }
        return Transliteration(segments, hasPinyin)
    }

    private fun pinyinSegment(codePoint: Int, primary: String): Segment {
        val normalizedPrimary = normalizeSyllable(primary)
        return Segment(
            primary = normalizedPrimary,
            initial = normalizedPrimary.take(1),
            alternatives = pronunciations(codePoint).filterNot { it == normalizedPrimary },
        )
    }

    private fun pronunciations(codePoint: Int): List<String> {
        if (codePoint > Char.MAX_VALUE.code) return emptyList()
        return PinyinHelper.toHanyuPinyinStringArray(codePoint.toChar(), outputFormat)
            ?.map(::normalizeSyllable)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            .orEmpty()
    }

    private fun normalizeSyllable(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ü', 'v')
        .filterNot(Char::isDigit)

    private data class Segment(
        val primary: String,
        val initial: String,
        val alternatives: List<String> = emptyList(),
    )

    private data class Transliteration(
        val segments: List<Segment>,
        val hasPinyin: Boolean,
    ) {
        fun full(replacementIndex: Int = -1, replacement: String = ""): String =
            normalizedStream { index, segment ->
                if (index == replacementIndex) replacement else segment.primary
            }

        fun initials(): String = normalizedStream { _, segment -> segment.initial }

        private fun normalizedStream(value: (Int, Segment) -> String): String = buildString {
            segments.forEachIndexed { index, segment -> append(value(index, segment)) }
        }.replace(Regex(" +"), " ").trim()
    }

    private class PhraseTrie private constructor(private val root: Node) {
        fun longestMatch(text: String, startOffset: Int): PhraseMatch? {
            var node = root
            var offset = startOffset
            var longest: PhraseMatch? = null
            val codePoints = mutableListOf<Int>()
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                node = node.children[codePoint] ?: break
                codePoints += codePoint
                offset += Character.charCount(codePoint)
                node.syllables?.let { syllables ->
                    longest = PhraseMatch(offset, codePoints.toList(), syllables)
                }
            }
            return longest
        }

        data class Node(
            val children: Map<Int, Node>,
            val syllables: List<String>?,
        )

        companion object {
            fun load(lines: List<String>): PhraseTrie {
                val root = MutableNode()
                lines.asSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .forEach { line ->
                        val columns = line.split('\t')
                        require(columns.size == 2) { "Invalid pinyin phrase row: $line" }
                        val codePoints = columns[0].codePoints().toArray()
                        val syllables = columns[1].split(Regex(" +")).map(::normalizeSyllable)
                        require(codePoints.size == syllables.size) {
                            "Pinyin phrase syllable count mismatch: ${columns[0]}"
                        }
                        var node = root
                        codePoints.forEach { codePoint ->
                            node = node.children.getOrPut(codePoint, ::MutableNode)
                        }
                        require(node.syllables == null) { "Duplicate pinyin phrase: ${columns[0]}" }
                        node.syllables = syllables.toList()
                    }
                return PhraseTrie(root.freeze())
            }
        }

        private class MutableNode(
            val children: MutableMap<Int, MutableNode> = linkedMapOf(),
            var syllables: List<String>? = null,
        ) {
            fun freeze(): Node = Node(
                children = children.mapValues { (_, child) -> child.freeze() }.toMap(),
                syllables = syllables?.toList(),
            )
        }
    }

    private data class PhraseMatch(
        val endOffset: Int,
        val codePoints: List<Int>,
        val syllables: List<String>,
    )
}
