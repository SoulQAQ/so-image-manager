package cn.soul2.imageai.search

import cn.soul2.imageai.analysis.EffectiveProjectionSnapshot
import cn.soul2.imageai.analysis.SearchProjectionPreparation
import cn.soul2.imageai.analysis.SearchProjectionWriter
import cn.soul2.imageai.data.db.dao.SearchIndexDao
import cn.soul2.imageai.data.db.entity.ImageSearchTermEntity
import cn.soul2.imageai.data.db.entity.SearchGramEntity
import cn.soul2.imageai.data.db.entity.SearchGramOwnerType
import cn.soul2.imageai.data.db.entity.SearchSourceChunkEntity
import cn.soul2.imageai.data.db.entity.SearchTermAliasEntity
import cn.soul2.imageai.data.db.entity.SearchTermEntity
import cn.soul2.imageai.data.db.entity.SearchTextAliasChunkEntity

class RoomSearchProjectionWriter internal constructor(
    private val dao: SearchIndexDao,
    private val planner: SearchProjectionPlanner = SearchProjectionPlanner(),
    private val afterDocumentWrite: () -> Unit = {},
) : SearchProjectionWriter {
    private class Prepared(
        val writer: RoomSearchProjectionWriter,
        val plan: SearchProjectionPlan,
    ) : SearchProjectionPreparation.Ready

    override fun prepareForImage(
        imageLocalId: Long,
        snapshot: EffectiveProjectionSnapshot,
    ): SearchProjectionPreparation {
        val image = requireNotNull(dao.getImage(imageLocalId)) { "search projection image does not exist" }
        val plan = planner.plan(image, snapshot)
        return if (plan.relationshipCount > SearchLimits.RELATIONSHIPS_PER_IMAGE) {
            SearchProjectionPreparation.Blocked(
                code = "SEARCH_INDEX_LIMIT",
                detail = "${plan.relationshipCount} search relationships exceed " +
                    SearchLimits.RELATIONSHIPS_PER_IMAGE,
            )
        } else {
            Prepared(this, plan)
        }
    }

    override fun replaceForImage(preparation: SearchProjectionPreparation.Ready) {
        require(preparation is Prepared && preparation.writer === this) {
            "preparation belongs to another search projection writer"
        }
        val plan = preparation.plan
        val imageId = plan.document.imageLocalId
        dao.deleteImageIndex(imageId)
        dao.upsertDocument(plan.document)
        afterDocumentWrite()

        val termIds = linkedMapOf<String, Long>()
        plan.terms.forEach { term ->
            val inserted = dao.insertTerm(
                SearchTermEntity(
                    normalizedKey = term.normalizedKey,
                    displayValue = term.displayValue,
                    unitType = term.unitType,
                ),
            )
            termIds[term.normalizedKey] = if (inserted != -1L) {
                inserted
            } else {
                requireNotNull(dao.getTerm(term.normalizedKey)).termId
            }
        }
        dao.upsertImageTerms(
            plan.mappings.map { mapping ->
                ImageSearchTermEntity(
                    imageLocalId = imageId,
                    termId = termIds.getValue(mapping.normalizedKey),
                    fieldMask = mapping.field.mask,
                    ownership = mapping.ownership,
                    weight = mapping.weight,
                )
            },
        )

        val aliasIds = linkedMapOf<PlannedSearchAlias, Long>()
        plan.aliases.forEach { alias ->
            val termId = termIds.getValue(alias.normalizedKey)
            val inserted = dao.insertAliases(
                listOf(SearchTermAliasEntity(termId = termId, aliasType = alias.aliasType, aliasText = alias.text)),
            ).single()
            aliasIds[alias] = if (inserted != -1L) {
                inserted
            } else {
                requireNotNull(dao.getAlias(termId, alias.aliasType, alias.text)).aliasId
            }
        }

        val sourceIds = dao.insertSourceChunks(
            plan.sourceChunks.map { chunk ->
                SearchSourceChunkEntity(
                    imageLocalId = imageId,
                    field = chunk.field.name,
                    ordinal = chunk.ordinal,
                    normalizedText = chunk.text,
                )
            },
        )
        val sourceIdByKey = plan.sourceChunks.zip(sourceIds).associate { (chunk, id) ->
            (chunk.field to chunk.ordinal) to id
        }
        val textAliasIds = dao.insertTextAliasChunks(
            plan.textAliasChunks.map { chunk ->
                SearchTextAliasChunkEntity(
                    imageLocalId = imageId,
                    field = chunk.field.name,
                    aliasType = chunk.aliasType,
                    ordinal = chunk.ordinal,
                    aliasText = chunk.text,
                )
            },
        )
        val textAliasIdByKey = plan.textAliasChunks.zip(textAliasIds).associate { (chunk, id) ->
            Triple(chunk.field, chunk.aliasType, chunk.ordinal) to id
        }

        dao.deleteUnreferencedTerms()
        dao.deleteOrphanGrams()
        dao.upsertGrams(
            plan.grams.map { gram ->
                val ownerType: SearchGramOwnerType
                val ownerId: Long
                when (val owner = gram.owner) {
                    is PlannedGramOwner.Term -> {
                        ownerType = SearchGramOwnerType.TERM
                        ownerId = termIds.getValue(owner.normalizedKey)
                    }
                    is PlannedGramOwner.Alias -> {
                        ownerType = SearchGramOwnerType.TERM_ALIAS
                        ownerId = aliasIds.getValue(
                            PlannedSearchAlias(owner.normalizedKey, owner.aliasType, owner.text),
                        )
                    }
                    is PlannedGramOwner.SourceChunk -> {
                        ownerType = SearchGramOwnerType.SOURCE_CHUNK
                        ownerId = sourceIdByKey.getValue(owner.field to owner.ordinal)
                    }
                    is PlannedGramOwner.TextAliasChunk -> {
                        ownerType = SearchGramOwnerType.TEXT_ALIAS_CHUNK
                        ownerId = textAliasIdByKey.getValue(
                            Triple(owner.field, owner.aliasType, owner.ordinal),
                        )
                    }
                }
                SearchGramEntity(gram.gram, ownerType, ownerId)
            },
        )
    }
}
