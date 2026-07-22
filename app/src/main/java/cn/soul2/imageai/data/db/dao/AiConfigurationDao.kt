package cn.soul2.imageai.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ProtocolDefinitionEntity
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AiConfigurationDao {
    @Query("SELECT * FROM provider_profile ORDER BY display_name ASC, provider_id ASC")
    fun observeProviders(): Flow<List<ProviderProfileEntity>>

    @Query("SELECT * FROM model_profile ORDER BY display_name ASC, model_profile_id ASC")
    fun observeModels(): Flow<List<ModelProfileEntity>>

    @Query("SELECT * FROM protocol_definition ORDER BY display_name ASC, protocol_definition_id ASC")
    fun observeProtocols(): Flow<List<ProtocolDefinitionEntity>>

    @Query("SELECT * FROM ai_runtime_setting WHERE singleton_id = 1 LIMIT 1")
    fun observeRuntimeSetting(): Flow<AiRuntimeSettingEntity?>

    @Query("SELECT * FROM provider_profile WHERE provider_id = :providerId LIMIT 1")
    suspend fun getProvider(providerId: String): ProviderProfileEntity?

    @Query("SELECT * FROM model_profile WHERE model_profile_id = :modelProfileId LIMIT 1")
    suspend fun getModel(modelProfileId: String): ModelProfileEntity?

    @Query("SELECT * FROM model_profile WHERE enabled = 1 AND supports_vision = 1 ORDER BY model_profile_id ASC")
    suspend fun getEnabledVisionModels(): List<ModelProfileEntity>

    @Query("SELECT * FROM protocol_definition WHERE protocol_definition_id = :protocolId LIMIT 1")
    suspend fun getProtocol(protocolId: String): ProtocolDefinitionEntity?

    @Query("SELECT * FROM ai_runtime_setting WHERE singleton_id = 1 LIMIT 1")
    suspend fun getRuntimeSetting(): AiRuntimeSettingEntity?

    @Upsert
    suspend fun upsertProvider(provider: ProviderProfileEntity)

    @Upsert
    suspend fun upsertModel(model: ModelProfileEntity)

    @Upsert
    suspend fun upsertProtocol(protocol: ProtocolDefinitionEntity)

    @Upsert
    suspend fun upsertRuntimeSetting(setting: AiRuntimeSettingEntity)

    @Query("DELETE FROM provider_profile WHERE provider_id = :providerId")
    suspend fun deleteProvider(providerId: String): Int

    @Query("DELETE FROM model_profile WHERE model_profile_id = :modelProfileId")
    suspend fun deleteModel(modelProfileId: String): Int

    @Query("DELETE FROM protocol_definition WHERE protocol_definition_id = :protocolId")
    suspend fun deleteProtocol(protocolId: String): Int
}
