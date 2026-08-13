package cn.soul2.imageai.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cn.soul2.imageai.data.db.dao.AppSettingDao
import cn.soul2.imageai.data.db.dao.AnalysisDao
import cn.soul2.imageai.data.db.dao.EffectiveMetadataDao
import cn.soul2.imageai.data.db.dao.ImageDao
import cn.soul2.imageai.data.db.dao.MediaSyncDao
import cn.soul2.imageai.data.db.dao.SearchIndexDao
import cn.soul2.imageai.data.db.dao.AiConfigurationDao
import cn.soul2.imageai.data.db.dao.BatchAnalysisDao
import cn.soul2.imageai.data.db.entity.AppSettingEntity
import cn.soul2.imageai.data.db.entity.ActiveImageAnalysisEntity
import cn.soul2.imageai.data.db.entity.AnalysisActivationDiagnosticEntity
import cn.soul2.imageai.data.db.entity.AnalysisTermEntity
import cn.soul2.imageai.data.db.entity.EffectiveImageMetadataEntity
import cn.soul2.imageai.data.db.entity.EffectiveImageTermEntity
import cn.soul2.imageai.data.db.entity.ImageEntity
import cn.soul2.imageai.data.db.entity.ImageAnalysisEntity
import cn.soul2.imageai.data.db.entity.ImageUserCorrectionEntity
import cn.soul2.imageai.data.db.entity.MediaSyncCheckpointEntity
import cn.soul2.imageai.data.db.entity.MediaSyncRunEntity
import cn.soul2.imageai.data.db.entity.UserTermOverrideEntity
import cn.soul2.imageai.data.db.entity.ImageSearchTermEntity
import cn.soul2.imageai.data.db.entity.SearchDocumentEntity
import cn.soul2.imageai.data.db.entity.SearchDocumentFtsEntity
import cn.soul2.imageai.data.db.entity.SearchGramEntity
import cn.soul2.imageai.data.db.entity.SearchSourceChunkEntity
import cn.soul2.imageai.data.db.entity.SearchTermAliasEntity
import cn.soul2.imageai.data.db.entity.SearchTermEntity
import cn.soul2.imageai.data.db.entity.SearchTextAliasChunkEntity
import cn.soul2.imageai.data.db.entity.AiRuntimeSettingEntity
import cn.soul2.imageai.data.db.entity.ModelProfileEntity
import cn.soul2.imageai.data.db.entity.ProtocolDefinitionEntity
import cn.soul2.imageai.data.db.entity.ProviderProfileEntity
import cn.soul2.imageai.data.db.entity.BatchAnalysisRunEntity
import cn.soul2.imageai.data.db.entity.BatchAnalysisItemEntity
import cn.soul2.imageai.data.db.entity.ProviderRouteEntity

@Database(
    entities = [
        AppSettingEntity::class,
        ImageEntity::class,
        MediaSyncCheckpointEntity::class,
        MediaSyncRunEntity::class,
        ImageAnalysisEntity::class,
        AnalysisTermEntity::class,
        ActiveImageAnalysisEntity::class,
        ImageUserCorrectionEntity::class,
        UserTermOverrideEntity::class,
        EffectiveImageMetadataEntity::class,
        EffectiveImageTermEntity::class,
        AnalysisActivationDiagnosticEntity::class,
        SearchDocumentEntity::class,
        SearchDocumentFtsEntity::class,
        SearchTermEntity::class,
        ImageSearchTermEntity::class,
        SearchTermAliasEntity::class,
        SearchSourceChunkEntity::class,
        SearchTextAliasChunkEntity::class,
        SearchGramEntity::class,
        ProviderProfileEntity::class,
        ProtocolDefinitionEntity::class,
        ModelProfileEntity::class,
        AiRuntimeSettingEntity::class,
        BatchAnalysisRunEntity::class,
        BatchAnalysisItemEntity::class,
        ProviderRouteEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appSettingDao(): AppSettingDao
    abstract fun imageDao(): ImageDao
    abstract fun mediaSyncDao(): MediaSyncDao
    internal abstract fun analysisDao(): AnalysisDao
    internal abstract fun effectiveMetadataDao(): EffectiveMetadataDao
    internal abstract fun searchIndexDao(): SearchIndexDao
    internal abstract fun aiConfigurationDao(): AiConfigurationDao
    internal abstract fun batchAnalysisDao(): BatchAnalysisDao
}
