package cn.soul2.imageai.search

import cn.soul2.imageai.data.db.entity.SearchDocumentEntity
import cn.soul2.imageai.data.db.entity.SearchTermOwnership
import cn.soul2.imageai.data.db.entity.SearchTermUnitType

data class PlannedSearchTerm(
    val normalizedKey: String,
    val displayValue: String,
    val unitType: SearchTermUnitType,
)

data class PlannedSearchMapping(
    val normalizedKey: String,
    val field: SearchField,
    val ownership: SearchTermOwnership,
    val weight: Double,
)

data class PlannedSearchAlias(
    val normalizedKey: String,
    val aliasType: PinyinAliasType,
    val text: String,
)

data class PlannedSourceChunk(
    val field: SearchField,
    val ordinal: Int,
    val text: String,
)

data class PlannedTextAliasChunk(
    val field: SearchField,
    val aliasType: PinyinAliasType,
    val ordinal: Int,
    val text: String,
)

sealed interface PlannedGramOwner {
    data class Term(val normalizedKey: String) : PlannedGramOwner
    data class Alias(
        val normalizedKey: String,
        val aliasType: PinyinAliasType,
        val text: String,
    ) : PlannedGramOwner
    data class SourceChunk(val field: SearchField, val ordinal: Int) : PlannedGramOwner
    data class TextAliasChunk(
        val field: SearchField,
        val aliasType: PinyinAliasType,
        val ordinal: Int,
    ) : PlannedGramOwner
}

data class PlannedSearchGram(
    val gram: String,
    val owner: PlannedGramOwner,
)

data class SearchProjectionPlan(
    val document: SearchDocumentEntity,
    val terms: List<PlannedSearchTerm>,
    val mappings: List<PlannedSearchMapping>,
    val aliases: List<PlannedSearchAlias>,
    val sourceChunks: List<PlannedSourceChunk>,
    val textAliasChunks: List<PlannedTextAliasChunk>,
    val grams: List<PlannedSearchGram>,
    val relationshipCount: Int,
)
