package cn.soul2.imageai.search

import cn.soul2.imageai.analysis.EffectiveProjectionSnapshot
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.EffectiveTermSource
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.SearchDocumentEntity
import cn.soul2.imageai.data.db.entity.SearchTermOwnership
import cn.soul2.imageai.data.db.entity.SearchTermUnitType
import java.text.Normalizer
import java.util.Locale

class SearchProjectionPlanner {
    fun plan(image: ImageEntity, snapshot: EffectiveProjectionSnapshot): SearchProjectionPlan {
        require(image.localId > 0L && snapshot.metadata.imageLocalId == image.localId) {
            "image and effective projection must identify the same persisted image"
        }

        val fileName = normalize(image.displayName)
        val album = normalize(image.bucketName.orEmpty())
        val caption = normalize(snapshot.metadata.caption.orEmpty())
        val tags = snapshot.terms.filter { it.kind == AnalysisTermKind.TAG }
        val categories = snapshot.terms.filter { it.kind == AnalysisTermKind.CATEGORY }
        val tokens = snapshot.terms.filter { it.kind == AnalysisTermKind.SEARCH_TOKEN }
        val mediaText = normalize(
            listOfNotNull(
                "${image.width}x${image.height}",
                image.width.toString(),
                image.height.toString(),
                image.sizeBytes.toString(),
                image.capturedAtEpochMillis?.toString(),
                image.addedAtEpochMillis.toString(),
                image.modifiedAtEpochMillis.toString(),
                image.sortTimeEpochMillis.toString(),
            ).joinToString(" "),
        )

        val termsByKey = linkedMapOf<String, PlannedSearchTerm>()
        val mappings = linkedSetOf<PlannedSearchMapping>()
        val aliases = linkedSetOf<PlannedSearchAlias>()
        val sourceChunks = linkedSetOf<PlannedSourceChunk>()
        val textAliasChunks = linkedSetOf<PlannedTextAliasChunk>()
        val grams = linkedSetOf<PlannedSearchGram>()

        fun addTerm(
            key: String,
            displayValue: String,
            field: SearchField,
            ownership: SearchTermOwnership,
            weight: Double,
        ) {
            val normalizedKey = normalize(key)
            if (normalizedKey.isEmpty()) return
            termsByKey.putIfAbsent(
                normalizedKey,
                PlannedSearchTerm(normalizedKey, normalizeDisplay(displayValue), unitType(normalizedKey)),
            )
            mappings += PlannedSearchMapping(normalizedKey, field, ownership, weight)
            SearchGramGenerator.grams(normalizedKey).forEach { gram ->
                grams += PlannedSearchGram(gram, PlannedGramOwner.Term(normalizedKey))
            }
            PinyinTransliterator.lexicalAliases(normalizedKey).forEach { alias ->
                val planned = PlannedSearchAlias(normalizedKey, alias.type, alias.text)
                aliases += planned
                SearchGramGenerator.grams(alias.text).forEach { gram ->
                    grams += PlannedSearchGram(
                        gram,
                        PlannedGramOwner.Alias(normalizedKey, alias.type, alias.text),
                    )
                }
            }
        }

        fun addCompleteField(field: SearchField, text: String) {
            if (text.isEmpty()) return
            OverlappingChunker.chunkByCodePoints(text).forEach { chunk ->
                val planned = PlannedSourceChunk(field, chunk.ordinal, chunk.text)
                sourceChunks += planned
                SearchGramGenerator.grams(chunk.text).forEach { gram ->
                    grams += PlannedSearchGram(gram, PlannedGramOwner.SourceChunk(field, chunk.ordinal))
                }
            }
            if (!containsHan(text)) return
            val streams = PinyinTransliterator.completeStreams(text)
            listOf(
                PinyinAliasType.FULL to streams.fullChunks,
                PinyinAliasType.INITIALS to streams.initialsChunks,
            ).forEach { (type, chunks) ->
                chunks.forEach { chunk ->
                    val planned = PlannedTextAliasChunk(field, type, chunk.ordinal, chunk.text)
                    textAliasChunks += planned
                    SearchGramGenerator.grams(chunk.text).forEach { gram ->
                        grams += PlannedSearchGram(
                            gram,
                            PlannedGramOwner.TextAliasChunk(field, type, chunk.ordinal),
                        )
                    }
                }
            }
        }

        addTerm(fileName, image.displayName, SearchField.FILE_NAME, SearchTermOwnership.MEDIA, 450.0)
        addTerm(album, image.bucketName.orEmpty(), SearchField.ALBUM, SearchTermOwnership.MEDIA, 450.0)
        tags.forEach { term ->
            addTerm(
                term.normalizedKey,
                term.displayValue,
                SearchField.TAG,
                term.source.toOwnership(),
                if (term.source == EffectiveTermSource.USER) 600.0 else 500.0,
            )
        }
        categories.forEach { term ->
            addTerm(
                term.normalizedKey,
                term.displayValue,
                SearchField.CATEGORY,
                term.source.toOwnership(),
                if (term.source == EffectiveTermSource.USER) 600.0 else 500.0,
            )
        }
        tokens.forEach { term ->
            addTerm(term.normalizedKey, term.displayValue, SearchField.SEARCH_TOKEN, SearchTermOwnership.AI, 350.0)
        }

        addCompleteField(SearchField.FILE_NAME, fileName)
        addCompleteField(SearchField.ALBUM, album)
        addCompleteField(SearchField.CAPTION, caption)
        addCompleteField(SearchField.MEDIA_TEXT, mediaText)

        val sourceList = sourceChunks.toList()
        val aliasChunkList = textAliasChunks.toList()
        val mappingList = mappings.toList()
        return SearchProjectionPlan(
            document = SearchDocumentEntity(
                imageLocalId = image.localId,
                fileName = fileName,
                album = album,
                caption = caption,
                tags = tags.joinToString(" ") { it.normalizedKey },
                categories = categories.joinToString(" ") { it.normalizedKey },
                searchTokens = tokens.joinToString(" ") { it.normalizedKey },
                mediaText = mediaText,
            ),
            terms = termsByKey.values.toList(),
            mappings = mappingList,
            aliases = aliases.toList(),
            sourceChunks = sourceList,
            textAliasChunks = aliasChunkList,
            grams = grams.toList(),
            relationshipCount = mappingList.size + sourceList.size + aliasChunkList.size,
        )
    }

    private fun normalize(value: String): String {
        requireValidSearchUtf16(value, "search projection text")
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\p{Z}\\s]+"), " ")
            .trim()
    }

    private fun normalizeDisplay(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("[\\p{Z}\\s]+"), " ")
        .trim()

    private fun unitType(value: String): SearchTermUnitType {
        var hasCjk = false
        var hasLatinDigit = false
        value.codePoints().forEach { codePoint ->
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL,
                -> hasCjk = true
                Character.UnicodeScript.LATIN -> hasLatinDigit = true
                else -> if (Character.isDigit(codePoint)) hasLatinDigit = true
            }
        }
        return when {
            hasCjk && hasLatinDigit -> SearchTermUnitType.MIXED
            hasCjk -> SearchTermUnitType.CJK
            else -> SearchTermUnitType.LATIN_DIGIT
        }
    }

    private fun containsHan(value: String): Boolean = value.codePoints().anyMatch {
        Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
    }

    private fun EffectiveTermSource.toOwnership(): SearchTermOwnership = when (this) {
        EffectiveTermSource.AI -> SearchTermOwnership.AI
        EffectiveTermSource.USER -> SearchTermOwnership.USER
    }
}
