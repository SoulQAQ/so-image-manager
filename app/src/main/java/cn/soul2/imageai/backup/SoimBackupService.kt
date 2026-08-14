package cn.soul2.imageai.backup

import androidx.room.withTransaction
import cn.soul2.imageai.ai.config.AiConfigurationRepository
import cn.soul2.imageai.analysis.CanonicalAnalysisDraft
import cn.soul2.imageai.analysis.CanonicalMetadataRepository
import cn.soul2.imageai.analysis.CanonicalTermInput
import cn.soul2.imageai.analysis.CorrectionCommand
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.AppSettingEntity
import cn.soul2.imageai.data.db.entity.CaptionCorrectionMode
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.ImagePartition
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ModelProtocolType
import cn.soul2.imageai.data.db.entity.ProtocolDefinitionEntity
import cn.soul2.imageai.data.db.entity.ProviderAuthMode
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import cn.soul2.imageai.data.db.entity.ProviderRouteEntity
import cn.soul2.imageai.data.db.entity.UserTermOverrideAction
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class BackupExportResult(val imageCount: Int, val analysisCount: Int)

data class BackupRestoreResult(
    val matchedImages: Int,
    val unmatchedImages: Int,
    val restoredAnalyses: Int,
    val conflicts: Int,
)

data class BackupPreflightResult(
    val format: String,
    val version: Int,
    val imageCount: Int,
    val analysisCount: Int,
    val correctionCount: Int,
    val providerCount: Int,
    val modelCount: Int,
    val protocolCount: Int,
    val credentialReentryCount: Int,
    val credentialsIncluded: Boolean,
)

class SoimBackupService(
    private val database: AppDatabase,
    private val canonicalRepository: CanonicalMetadataRepository,
    private val configurationRepository: AiConfigurationRepository = AiConfigurationRepository(database),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun exportJson(): Pair<String, BackupExportResult> {
        val snapshot = database.withTransaction {
            BackupSnapshot(
                images = database.imageDao().getBackupCandidates(),
                analyses = database.analysisDao().getAllAnalyses(),
                terms = database.analysisDao().getAllTerms(),
                active = database.analysisDao().getAllActive().associate { it.imageLocalId to it.analysisId },
                corrections = database.effectiveMetadataDao().getAllCorrections(),
                overrides = database.effectiveMetadataDao().getAllOverrides(),
                providers = database.aiConfigurationDao().getAllProviders(),
                models = database.aiConfigurationDao().getAllModels(),
                protocols = database.aiConfigurationDao().getAllProtocols(),
                routes = database.aiConfigurationDao().getAllRoutes(),
                runtime = database.aiConfigurationDao().getRuntimeSetting(),
                settings = database.appSettingDao().getAll(),
            )
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", FORMAT_VERSION)
            .put("createdAtEpochMillis", nowEpochMillis())
            .put("credentialsIncluded", false)
            .put("images", JSONArray(snapshot.images.map(::imageJson)))
            .put("analyses", JSONArray(snapshot.analyses.map { analysis ->
                val terms = snapshot.terms.filter { it.analysisId == analysis.analysisId }
                JSONObject()
                    .put("analysisId", analysis.analysisId)
                    .put("imageLocalId", analysis.imageLocalId)
                    .put("active", snapshot.active[analysis.imageLocalId] == analysis.analysisId)
                    .put("schemaVersion", analysis.schemaVersion)
                    .put("caption", analysis.caption)
                    .putNullable("extensionJson", analysis.extensionJson)
                    .put("providerProfileId", analysis.providerProfileId)
                    .put("modelProfileId", analysis.modelProfileId)
                    .put("protocolDefinitionId", analysis.protocolDefinitionId)
                    .put("promptTemplateId", analysis.promptTemplateId)
                    .put("createdAtEpochMillis", analysis.createdAtEpochMillis)
                    .put("completedAtEpochMillis", analysis.completedAtEpochMillis)
                    .put("terms", JSONArray(terms.map { term ->
                        JSONObject()
                            .put("kind", term.kind.name)
                            .put("value", term.displayValue)
                            .putNullable("confidence", term.confidence)
                    }))
            }))
            .put("corrections", JSONArray(snapshot.corrections.map { correction ->
                JSONObject()
                    .put("imageLocalId", correction.imageLocalId)
                    .put("mode", correction.captionMode.name)
                    .putNullable("value", correction.captionValue)
            }))
            .put("termOverrides", JSONArray(snapshot.overrides.map { override ->
                JSONObject()
                    .put("imageLocalId", override.imageLocalId)
                    .put("kind", override.kind.name)
                    .put("action", override.action.name)
                    .put("value", override.displayValue ?: override.normalizedKey)
            }))
            .put("providers", JSONArray(snapshot.providers.map(::providerJson)))
            .put("models", JSONArray(snapshot.models.map(::modelJson)))
            .put("protocols", JSONArray(snapshot.protocols.map(::protocolJson)))
            .put("routes", JSONArray(snapshot.routes.map(::routeJson)))
            .putNullable("runtime", snapshot.runtime?.let(::runtimeJson))
            .put("settings", JSONArray(snapshot.settings.map { setting ->
                JSONObject()
                    .put("key", setting.key)
                    .put("valueJson", setting.valueJson)
                    .put("updatedAtEpochMillis", setting.updatedAtEpochMillis)
            }))
        return root.toString(2) to BackupExportResult(snapshot.images.size, snapshot.analyses.size)
    }

    suspend fun restoreJson(text: String): BackupRestoreResult {
        val validated = validateBackup(text)
        val root = validated.root
        val configuration = validated.configuration
        restoreConfiguration(configuration)
        val currentImages = database.imageDao().getBackupCandidates()
        val backupImages = validated.images
        val mapping = matchImages(backupImages, currentImages)
        var restoredAnalyses = 0
        var conflicts = 0
        root.requireArray("analyses").objects()
            .sortedWith(compareBy<JSONObject> { it.optBoolean("active") }.thenBy { it.getLong("completedAtEpochMillis") })
            .forEach { json ->
                val targetId = mapping[json.getLong("imageLocalId")] ?: return@forEach
                val originalId = json.getString("analysisId")
                val existing = database.analysisDao().getAnalysis(originalId)
                val analysisId = when {
                    existing == null || existing.imageLocalId == targetId -> originalId
                    else -> {
                        conflicts += 1
                        UUID.randomUUID().toString()
                    }
                }
                val terms = json.requireArray("terms").objects()
                val draft = CanonicalAnalysisDraft(
                            analysisId = analysisId,
                            imageLocalId = targetId,
                            schemaVersion = json.getInt("schemaVersion"),
                            caption = json.getString("caption"),
                            tags = terms.filterKind(AnalysisTermKind.TAG),
                            categories = terms.filterKind(AnalysisTermKind.CATEGORY),
                            searchTokens = terms.filter {
                                it.getString("kind") == AnalysisTermKind.SEARCH_TOKEN.name
                            }.map { it.getString("value") },
                            extensionJson = json.optNullableString("extensionJson"),
                            providerProfileId = json.getString("providerProfileId"),
                            modelProfileId = json.getString("modelProfileId"),
                            protocolDefinitionId = json.getString("protocolDefinitionId"),
                            promptTemplateId = json.getString("promptTemplateId"),
                            createdAtEpochMillis = json.getLong("createdAtEpochMillis"),
                            completedAtEpochMillis = json.getLong("completedAtEpochMillis"),
                        )
                val result = runCatching {
                    if (json.optBoolean("active")) {
                        canonicalRepository.activateAnalysis(draft)
                    } else {
                        canonicalRepository.importAnalysisHistory(draft)
                    }
                }
                if (result.isSuccess) restoredAnalyses += 1 else conflicts += 1
            }
        restoreCorrections(root, mapping)
        backupImages.forEach { ref ->
            val localId = mapping[ref.localId] ?: return@forEach
            runCatching { database.imageDao().restorePartition(localId, ref.partition) }
        }
        return BackupRestoreResult(
            matchedImages = mapping.size,
            unmatchedImages = backupImages.size - mapping.size,
            restoredAnalyses = restoredAnalyses,
            conflicts = conflicts,
        )
    }

    suspend fun preflightJson(text: String): BackupPreflightResult {
        val validated = validateBackup(text)
        val root = validated.root
        val providers = root.requireArray("providers").objects().map(::parseProvider)
        return BackupPreflightResult(
            format = root.getString("format"),
            version = root.getInt("version"),
            imageCount = validated.images.size,
            analysisCount = root.requireArray("analyses").length(),
            correctionCount = root.requireArray("corrections").length() +
                root.requireArray("termOverrides").length(),
            providerCount = providers.size,
            modelCount = root.requireArray("models").length(),
            protocolCount = root.requireArray("protocols").length(),
            credentialReentryCount = providers.count { it.authMode != ProviderAuthMode.NONE },
            credentialsIncluded = false,
        )
    }

    private suspend fun validateBackup(text: String): ValidatedBackup {
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("备份文件不是有效 JSON") }
        require(root.optString("format") == FORMAT && root.optInt("version") == FORMAT_VERSION) {
            "不支持的 SoIM 备份格式"
        }
        require(!root.optBoolean("credentialsIncluded", true)) { "备份不得包含 API Key" }
        val configuration = parseAndValidateConfiguration(root)
        validatePortableData(root)
        return ValidatedBackup(
            root = root,
            configuration = configuration,
            images = root.requireArray("images").objects().map(::parseImageRef),
        )
    }

    private suspend fun parseAndValidateConfiguration(root: JSONObject): ImportedConfiguration {
        val dao = database.aiConfigurationDao()
        val existingProviders = dao.getAllProviders().associateBy { it.providerId }
        val protocols = root.requireArray("protocols").objects().map(::parseProtocol)
        val providers = root.requireArray("providers").objects().map { json ->
            val imported = parseProvider(json)
            existingProviders[imported.providerId]?.let { existing ->
                imported.copy(
                    authMode = existing.authMode,
                    authHeaderName = existing.authHeaderName,
                    authPrefix = existing.authPrefix,
                    credentialId = existing.credentialId,
                )
            } ?: imported.copy(
                authMode = ProviderAuthMode.NONE,
                authHeaderName = null,
                authPrefix = null,
                credentialId = null,
                enabled = false,
            )
        }
        val models = root.requireArray("models").objects().map(::parseModel)
        val providerIds = providers.mapTo(hashSetOf()) { it.providerId }
        val protocolIds = protocols.mapTo(hashSetOf()) { it.protocolDefinitionId }
        val validModels = models.filter { model ->
            model.providerId in providerIds && (
                model.protocolType != ModelProtocolType.CUSTOM_JSON ||
                    model.protocolDefinitionId in protocolIds
                )
        }
        val modelIds = validModels.mapTo(hashSetOf()) { it.modelProfileId }
        val routes = root.requireArray("routes").objects().map(::parseRoute)
            .filter { it.providerId in providerIds }
            .groupBy { it.partition }
            .flatMap { (partition, entries) ->
                entries.distinctBy(ProviderRouteEntity::providerId)
                    .mapIndexed { index, route -> route.copy(partition = partition, position = index) }
            }
        val runtime = root.optJSONObject("runtime")?.let(::parseRuntime)?.let { imported ->
            imported.copy(
                defaultModelProfileId = imported.defaultModelProfileId?.takeIf { it in modelIds },
                automaticFailoverEnabled = true,
            )
        }
        val settings = root.requireArray("settings").objects().map { json ->
            AppSettingEntity(
                key = json.getString("key"),
                valueJson = json.getString("valueJson"),
                updatedAtEpochMillis = json.getLong("updatedAtEpochMillis"),
            )
        }.filterNot { it.key.startsWith("maintenance.") }

        protocols.forEach(configurationRepository::validateProtocol)
        providers.forEach(configurationRepository::validateProvider)
        validModels.forEach(configurationRepository::validateModel)
        runtime?.let(configurationRepository::validateRuntime)
        return ImportedConfiguration(protocols, providers, validModels, routes, runtime, settings)
    }

    private suspend fun restoreConfiguration(configuration: ImportedConfiguration) {
        val dao = database.aiConfigurationDao()
        database.withTransaction {
            configuration.protocols.forEach { protocol -> dao.upsertProtocol(protocol) }
            configuration.providers.forEach { provider -> dao.upsertProvider(provider) }
            configuration.models.forEach { model -> dao.upsertModel(model) }
            configuration.routes.groupBy { it.partition }.forEach { (partition, entries) ->
                dao.deleteRoutes(partition)
                if (entries.isNotEmpty()) dao.upsertRoutes(entries)
            }
            configuration.runtime?.let { dao.upsertRuntimeSetting(it) }
            if (configuration.settings.isNotEmpty()) {
                database.appSettingDao().upsertAll(configuration.settings)
            }
        }
    }

    private fun validatePortableData(root: JSONObject) {
        root.requireArray("images").objects().forEach(::parseImageRef)
        root.requireArray("analyses").objects().forEach { json ->
            json.getString("analysisId")
            json.getLong("imageLocalId")
            json.getBoolean("active")
            json.getInt("schemaVersion")
            json.getString("caption")
            json.optNullableString("extensionJson")
            json.getString("providerProfileId")
            json.getString("modelProfileId")
            json.getString("protocolDefinitionId")
            json.getString("promptTemplateId")
            json.getLong("createdAtEpochMillis")
            json.getLong("completedAtEpochMillis")
            json.requireArray("terms").objects().forEach { term ->
                AnalysisTermKind.valueOf(term.getString("kind"))
                term.getString("value")
                term.optNullableDouble("confidence")
            }
        }
        root.requireArray("corrections").objects().forEach { json ->
            json.getLong("imageLocalId")
            CaptionCorrectionMode.valueOf(json.getString("mode"))
            json.optNullableString("value")
        }
        root.requireArray("termOverrides").objects().forEach { json ->
            json.getLong("imageLocalId")
            AnalysisTermKind.valueOf(json.getString("kind"))
            UserTermOverrideAction.valueOf(json.getString("action"))
            json.getString("value")
        }
    }

    private suspend fun restoreCorrections(root: JSONObject, mapping: Map<Long, Long>) {
        root.requireArray("corrections").objects().forEach { json ->
            val localId = mapping[json.getLong("imageLocalId")] ?: return@forEach
            val command = when (CaptionCorrectionMode.valueOf(json.getString("mode"))) {
                CaptionCorrectionMode.SET -> CorrectionCommand.SetCaption(json.getString("value"))
                CaptionCorrectionMode.CLEARED -> CorrectionCommand.ClearCaption
                CaptionCorrectionMode.INHERIT -> CorrectionCommand.InheritCaption
            }
            runCatching { canonicalRepository.applyCorrection(localId, command, nowEpochMillis()) }
        }
        root.requireArray("termOverrides").objects().forEach { json ->
            val localId = mapping[json.getLong("imageLocalId")] ?: return@forEach
            val kind = AnalysisTermKind.valueOf(json.getString("kind"))
            if (kind == AnalysisTermKind.SEARCH_TOKEN) return@forEach
            val value = json.getString("value")
            val command = when (UserTermOverrideAction.valueOf(json.getString("action"))) {
                UserTermOverrideAction.ADD -> CorrectionCommand.AddTerm(kind, value)
                UserTermOverrideAction.TOMBSTONE -> CorrectionCommand.DeleteTerm(kind, value)
            }
            runCatching { canonicalRepository.applyCorrection(localId, command, nowEpochMillis()) }
        }
    }

    private fun matchImages(refs: List<ImageRef>, current: List<ImageEntity>): Map<Long, Long> {
        val byIdentity = current.associateBy { it.volumeName to it.mediaStoreId }
        val byFingerprint = current.groupBy { it.quickFingerprint }.filterValues { it.size == 1 }
        return refs.mapNotNull { ref ->
            val match = byIdentity[ref.volumeName to ref.mediaStoreId]
                ?: byFingerprint[ref.quickFingerprint]?.singleOrNull()
            match?.let { ref.localId to it.localId }
        }.toMap()
    }

    private data class BackupSnapshot(
        val images: List<ImageEntity>,
        val analyses: List<cn.soul2.imageai.data.db.entity.ImageAnalysisEntity>,
        val terms: List<cn.soul2.imageai.data.db.entity.AnalysisTermEntity>,
        val active: Map<Long, String>,
        val corrections: List<cn.soul2.imageai.data.db.entity.ImageUserCorrectionEntity>,
        val overrides: List<cn.soul2.imageai.data.db.entity.UserTermOverrideEntity>,
        val providers: List<ProviderProfileEntity>,
        val models: List<ModelProfileEntity>,
        val protocols: List<ProtocolDefinitionEntity>,
        val routes: List<ProviderRouteEntity>,
        val runtime: AiRuntimeSettingEntity?,
        val settings: List<AppSettingEntity>,
    )

    private data class ImportedConfiguration(
        val protocols: List<ProtocolDefinitionEntity>,
        val providers: List<ProviderProfileEntity>,
        val models: List<ModelProfileEntity>,
        val routes: List<ProviderRouteEntity>,
        val runtime: AiRuntimeSettingEntity?,
        val settings: List<AppSettingEntity>,
    )

    private data class ValidatedBackup(
        val root: JSONObject,
        val configuration: ImportedConfiguration,
        val images: List<ImageRef>,
    )

    companion object {
        const val MIME_TYPE = "application/json"
        const val DEFAULT_FILE_NAME = "soim-backup.json"
        private const val FORMAT = "soim-portable-backup"
        private const val FORMAT_VERSION = 1
    }
}

private fun imageJson(image: ImageEntity) = JSONObject()
    .put("localId", image.localId)
    .put("volumeName", image.volumeName)
    .put("mediaStoreId", image.mediaStoreId)
    .put("quickFingerprint", image.quickFingerprint)
    .put("displayName", image.displayName)
    .put("sizeBytes", image.sizeBytes)
    .put("modifiedAtEpochMillis", image.modifiedAtEpochMillis)
    .put("partition", image.partition.name)

private data class ImageRef(
    val localId: Long,
    val volumeName: String,
    val mediaStoreId: Long,
    val quickFingerprint: String,
    val partition: ImagePartition,
)

private fun parseImageRef(json: JSONObject) = ImageRef(
    localId = json.getLong("localId"),
    volumeName = json.getString("volumeName"),
    mediaStoreId = json.getLong("mediaStoreId"),
    quickFingerprint = json.getString("quickFingerprint"),
    partition = ImagePartition.valueOf(json.getString("partition")),
)

private fun providerJson(value: ProviderProfileEntity) = JSONObject()
    .put("providerId", value.providerId).put("displayName", value.displayName)
    .put("baseUrl", value.baseUrl).put("authMode", value.authMode.name)
    .putNullable("authHeaderName", value.authHeaderName).putNullable("authPrefix", value.authPrefix)
    .put("headersJson", value.headersJson).put("allowedRedirectOriginsJson", value.allowedRedirectOriginsJson)
    .put("cleartextApproved", value.cleartextApproved).put("connectTimeoutMillis", value.connectTimeoutMillis)
    .put("readTimeoutMillis", value.readTimeoutMillis).put("writeTimeoutMillis", value.writeTimeoutMillis)
    .put("maxConcurrency", value.maxConcurrency).put("requestsPerMinute", value.requestsPerMinute)
    .put("requestsPerDay", value.requestsPerDay).put("enabled", value.enabled)
    .put("createdAtEpochMillis", value.createdAtEpochMillis).put("updatedAtEpochMillis", value.updatedAtEpochMillis)

private fun parseProvider(j: JSONObject) = ProviderProfileEntity(
    j.getString("providerId"), j.getString("displayName"), j.getString("baseUrl"),
    ProviderAuthMode.valueOf(j.getString("authMode")), j.optNullableString("authHeaderName"),
    j.optNullableString("authPrefix"), null, j.getString("headersJson"),
    j.getString("allowedRedirectOriginsJson"), j.getBoolean("cleartextApproved"),
    j.getInt("connectTimeoutMillis"), j.getInt("readTimeoutMillis"), j.getInt("writeTimeoutMillis"),
    j.getInt("maxConcurrency"), j.getInt("requestsPerMinute"), j.getInt("requestsPerDay"),
    j.getBoolean("enabled"), j.getLong("createdAtEpochMillis"), j.getLong("updatedAtEpochMillis"),
)

private fun modelJson(v: ModelProfileEntity) = JSONObject()
    .put("modelProfileId", v.modelProfileId).put("providerId", v.providerId)
    .put("displayName", v.displayName).put("modelId", v.modelId).put("protocolType", v.protocolType.name)
    .putNullable("protocolDefinitionId", v.protocolDefinitionId).put("supportsVision", v.supportsVision)
    .putNullable("maxOutputTokens", v.maxOutputTokens).putNullable("temperature", v.temperature)
    .put("maxImageEdge", v.maxImageEdge).put("maxImageBytes", v.maxImageBytes)
    .put("maxConcurrency", v.maxConcurrency).put("requestsPerMinute", v.requestsPerMinute)
    .put("requestsPerDay", v.requestsPerDay).put("enabled", v.enabled)
    .put("createdAtEpochMillis", v.createdAtEpochMillis).put("updatedAtEpochMillis", v.updatedAtEpochMillis)

private fun parseModel(j: JSONObject) = ModelProfileEntity(
    j.getString("modelProfileId"), j.getString("providerId"), j.getString("displayName"),
    j.getString("modelId"), ModelProtocolType.valueOf(j.getString("protocolType")),
    j.optNullableString("protocolDefinitionId"), j.getBoolean("supportsVision"),
    j.optNullableInt("maxOutputTokens"), j.optNullableDouble("temperature"),
    j.getInt("maxImageEdge"), j.getInt("maxImageBytes"), j.getInt("maxConcurrency"),
    j.getInt("requestsPerMinute"), j.getInt("requestsPerDay"), j.getBoolean("enabled"),
    j.getLong("createdAtEpochMillis"), j.getLong("updatedAtEpochMillis"),
)

private fun protocolJson(v: ProtocolDefinitionEntity) = JSONObject()
    .put("protocolDefinitionId", v.protocolDefinitionId).put("displayName", v.displayName)
    .put("definitionJson", v.definitionJson).put("enabled", v.enabled)
    .put("createdAtEpochMillis", v.createdAtEpochMillis).put("updatedAtEpochMillis", v.updatedAtEpochMillis)

private fun parseProtocol(j: JSONObject) = ProtocolDefinitionEntity(
    j.getString("protocolDefinitionId"), j.getString("displayName"), j.getString("definitionJson"),
    j.getBoolean("enabled"), j.getLong("createdAtEpochMillis"), j.getLong("updatedAtEpochMillis"),
)

private fun routeJson(v: ProviderRouteEntity) = JSONObject().put("partition", v.partition.name)
    .put("providerId", v.providerId).put("position", v.position).put("enabled", v.enabled)

private fun parseRoute(j: JSONObject) = ProviderRouteEntity(
    ImagePartition.valueOf(j.getString("partition")), j.getString("providerId"),
    j.getInt("position"), j.getBoolean("enabled"),
)

private fun runtimeJson(v: AiRuntimeSettingEntity) = JSONObject()
    .putNullable("defaultModelProfileId", v.defaultModelProfileId)
    .put("globalMaxConcurrency", v.globalMaxConcurrency).put("globalRequestsPerMinute", v.globalRequestsPerMinute)
    .put("globalRequestsPerDay", v.globalRequestsPerDay).put("dailyImageLimit", v.dailyImageLimit)
    .put("dailyTokenLimit", v.dailyTokenLimit).put("wifiOnly", v.wifiOnly)
    .put("chargingOnly", v.chargingOnly).put("batteryNotLow", v.batteryNotLow)
    .put("executionStartMinute", v.executionStartMinute).put("executionEndMinute", v.executionEndMinute)
    .put("retryLimit", v.retryLimit).put("circuitBreakerThreshold", v.circuitBreakerThreshold)
    .put("circuitBreakerCooldownMinutes", v.circuitBreakerCooldownMinutes)
    .put("onlyShowAnalyzed", v.onlyShowAnalyzed).put("automaticFailoverEnabled", true)
    .put("promptText", v.promptText).put("updatedAtEpochMillis", v.updatedAtEpochMillis)

private fun parseRuntime(j: JSONObject) = AiRuntimeSettingEntity(
    defaultModelProfileId = j.optNullableString("defaultModelProfileId"),
    globalMaxConcurrency = j.getInt("globalMaxConcurrency"),
    globalRequestsPerMinute = j.getInt("globalRequestsPerMinute"),
    globalRequestsPerDay = j.getInt("globalRequestsPerDay"), dailyImageLimit = j.getInt("dailyImageLimit"),
    dailyTokenLimit = j.optLong("dailyTokenLimit", 0L),
    wifiOnly = j.optBoolean("wifiOnly", false),
    chargingOnly = j.optBoolean("chargingOnly", false),
    batteryNotLow = j.optBoolean("batteryNotLow", true),
    executionStartMinute = j.optInt("executionStartMinute", 0),
    executionEndMinute = j.optInt("executionEndMinute", 0),
    retryLimit = j.optInt("retryLimit", 4),
    circuitBreakerThreshold = j.optInt("circuitBreakerThreshold", 5),
    circuitBreakerCooldownMinutes = j.optInt("circuitBreakerCooldownMinutes", 30),
    onlyShowAnalyzed = j.getBoolean("onlyShowAnalyzed"), automaticFailoverEnabled = true,
    promptText = j.getString("promptText"), updatedAtEpochMillis = j.getLong("updatedAtEpochMillis"),
)

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.optNullableInt(key: String): Int? = if (isNull(key)) null else getInt(key)
private fun JSONObject.optNullableDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)
private fun JSONObject.requireArray(key: String): JSONArray = optJSONArray(key)
    ?: throw IllegalArgumentException("备份缺少 $key")
private fun JSONArray.objects(): List<JSONObject> = List(length()) { index -> getJSONObject(index) }
private fun List<JSONObject>.filterKind(kind: AnalysisTermKind): List<CanonicalTermInput> = filter {
    it.getString("kind") == kind.name
}.map { CanonicalTermInput(it.getString("value"), it.optNullableDouble("confidence")) }
