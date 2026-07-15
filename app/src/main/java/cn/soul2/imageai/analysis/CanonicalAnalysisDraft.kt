package cn.soul2.imageai.analysis

import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class CanonicalTermInput(
    val value: String,
    val confidence: Double? = null,
)

data class ValidatedCanonicalTerm(
    val displayValue: String,
    val normalizedKey: String,
    val confidence: Double?,
)

data class CanonicalAnalysisDraft(
    val analysisId: String,
    val imageLocalId: Long,
    val schemaVersion: Int,
    val caption: String,
    val tags: List<CanonicalTermInput>,
    val categories: List<CanonicalTermInput>,
    val searchTokens: List<String>,
    val extensionJson: String?,
    val extensionPersistenceBudgetBytes: Int = CanonicalLimits.DEFAULT_EXTENSION_JSON_UTF8_BYTES,
    val providerProfileId: String,
    val modelProfileId: String,
    val protocolDefinitionId: String,
    val promptTemplateId: String,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
) {
    fun validate(): ValidatedCanonicalAnalysis {
        val normalizedAnalysisId = runCatching { UUID.fromString(analysisId).toString() }
            .getOrElse { invalid("analysisId must be a UUID") }
        if (normalizedAnalysisId != analysisId.lowercase()) {
            invalid("analysisId must use canonical UUID form")
        }
        if (imageLocalId <= 0L) invalid("imageLocalId must be positive")
        if (schemaVersion <= 0) invalid("schemaVersion must be positive")
        if (createdAtEpochMillis < 0L || completedAtEpochMillis < createdAtEpochMillis) {
            invalid("analysis timestamps are invalid")
        }
        if (tags.size > CanonicalLimits.TAGS) invalid("too many tags")
        if (categories.size > CanonicalLimits.CATEGORIES) invalid("too many categories")
        if (searchTokens.size > CanonicalLimits.SEARCH_TOKENS) invalid("too many searchTokens")

        val normalizedCaption = MetadataNormalizer.normalizeCaption(caption)
        val normalizedTags = normalizeTerms(tags)
        val normalizedCategories = normalizeTerms(categories)
        val normalizedSearchTokens = normalizeTerms(searchTokens.map(::CanonicalTermInput))
        val normalizedExtension = validateExtensionJson(
            extensionJson,
            extensionPersistenceBudgetBytes,
        )
        val provider = MetadataNormalizer.normalizeIdentifier(providerProfileId, "providerProfileId")
        val model = MetadataNormalizer.normalizeIdentifier(modelProfileId, "modelProfileId")
        val protocol = MetadataNormalizer.normalizeIdentifier(
            protocolDefinitionId,
            "protocolDefinitionId",
        )
        val prompt = MetadataNormalizer.normalizeIdentifier(promptTemplateId, "promptTemplateId")

        val unsigned = ValidatedCanonicalAnalysis(
            analysisId = normalizedAnalysisId,
            imageLocalId = imageLocalId,
            schemaVersion = schemaVersion,
            caption = normalizedCaption,
            tags = normalizedTags,
            categories = normalizedCategories,
            searchTokens = normalizedSearchTokens,
            extensionJson = normalizedExtension,
            providerProfileId = provider,
            modelProfileId = model,
            protocolDefinitionId = protocol,
            promptTemplateId = prompt,
            createdAtEpochMillis = createdAtEpochMillis,
            completedAtEpochMillis = completedAtEpochMillis,
            contentHash = "",
        )
        return unsigned.copy(contentHash = unsigned.computeContentHash())
    }
}

data class ValidatedCanonicalAnalysis(
    val analysisId: String,
    val imageLocalId: Long,
    val schemaVersion: Int,
    val caption: String,
    val tags: List<ValidatedCanonicalTerm>,
    val categories: List<ValidatedCanonicalTerm>,
    val searchTokens: List<ValidatedCanonicalTerm>,
    val extensionJson: String?,
    val providerProfileId: String,
    val modelProfileId: String,
    val protocolDefinitionId: String,
    val promptTemplateId: String,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
    val contentHash: String,
)

private fun normalizeTerms(inputs: List<CanonicalTermInput>): List<ValidatedCanonicalTerm> {
    val normalized = linkedMapOf<String, ValidatedCanonicalTerm>()
    inputs.forEach { input ->
        val confidence = input.confidence
        if (confidence != null && (!confidence.isFinite() || confidence !in 0.0..1.0)) {
            invalid("term confidence must be between 0 and 1")
        }
        val term = MetadataNormalizer.normalizeTerm(input.value)
        val existing = normalized[term.normalizedKey]
        normalized[term.normalizedKey] = if (existing == null) {
            ValidatedCanonicalTerm(term.displayValue, term.normalizedKey, confidence)
        } else {
            existing.copy(confidence = maxOfNullable(existing.confidence, confidence))
        }
    }
    return normalized.values.toList()
}

private fun validateExtensionJson(value: String?, persistenceBudgetBytes: Int): String? {
    if (value == null) return null
    if (persistenceBudgetBytes !in 1..CanonicalLimits.EXTENSION_JSON_UTF8_BYTES) {
        invalid(
            "extension JSON budget must be between 1 and " +
                CanonicalLimits.EXTENSION_JSON_UTF8_BYTES,
        )
    }
    MetadataNormalizer.requireValidUtf16(value, "extensionJson")
    if (value.toByteArray(Charsets.UTF_8).size > persistenceBudgetBytes) {
        invalid("extensionJson exceeds the $persistenceBudgetBytes UTF-8 byte persistence budget")
    }
    CanonicalJsonValidator.validateRawNesting(value)
    val tokener = JSONTokener(value)
    val parsed = runCatching { tokener.nextValue() }
        .getOrElse { invalid("extensionJson must be valid JSON") }
    if (parsed !is JSONObject || tokener.nextClean().code != 0) {
        invalid("extensionJson must contain exactly one JSON object")
    }
    CanonicalJsonValidator.validateParsedTree(parsed)
    val canonical = canonicalJson(parsed)
    if (canonical.toByteArray(Charsets.UTF_8).size > persistenceBudgetBytes) {
        invalid(
            "extensionJson exceeds the $persistenceBudgetBytes UTF-8 byte persistence budget " +
                "after canonicalization",
        )
    }
    return canonical
}

private fun ValidatedCanonicalAnalysis.computeContentHash(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String?) {
        val bytes = value?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
        digest.update(';'.code.toByte())
    }
    add(imageLocalId.toString())
    add(schemaVersion.toString())
    add(caption)
    listOf(tags, categories, searchTokens).forEach { terms ->
        terms.sortedBy(ValidatedCanonicalTerm::normalizedKey).forEach { term ->
            add(term.normalizedKey)
            add(term.displayValue)
            add(term.confidence?.toString())
        }
        add(null)
    }
    add(extensionJson)
    add(providerProfileId)
    add(modelProfileId)
    add(protocolDefinitionId)
    add(promptTemplateId)
    add(createdAtEpochMillis.toString())
    add(completedAtEpochMillis.toString())
    return digest.digest().joinToString("") { byte -> "%02X".format(byte) }
}

private fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
        separator = ",",
        prefix = "{",
        postfix = "}",
    ) { key -> JSONObject.quote(key) + ":" + canonicalJson(value.get(key)) }
    is JSONArray -> (0 until value.length()).joinToString(
        separator = ",",
        prefix = "[",
        postfix = "]",
    ) { index -> canonicalJson(value.get(index)) }
    is String -> JSONObject.quote(value)
    is Number -> runCatching { JSONObject.numberToString(value) }
        .getOrElse { invalid("extensionJson contains an invalid number") }
    is Boolean -> value.toString()
    else -> invalid("extensionJson contains an unsupported value")
}

private fun maxOfNullable(first: Double?, second: Double?): Double? = when {
    first == null -> second
    second == null -> first
    else -> maxOf(first, second)
}
