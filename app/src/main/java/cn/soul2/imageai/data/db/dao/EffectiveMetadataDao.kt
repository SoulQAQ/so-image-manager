package cn.soul2.imageai.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.EffectiveImageMetadataEntity
import cn.soul2.imageai.data.db.entity.EffectiveImageTermEntity
import cn.soul2.imageai.data.db.entity.ImageUserCorrectionEntity
import cn.soul2.imageai.data.db.entity.UserTermOverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface EffectiveMetadataDao {
    @Query("SELECT * FROM image_user_correction WHERE image_local_id = :imageLocalId LIMIT 1")
    suspend fun getCorrection(imageLocalId: Long): ImageUserCorrectionEntity?

    @Query(
        """
        SELECT * FROM user_term_override
        WHERE image_local_id = :imageLocalId
        ORDER BY kind ASC, normalized_key ASC
        """,
    )
    suspend fun getOverrides(imageLocalId: Long): List<UserTermOverrideEntity>

    @Upsert
    suspend fun upsertCorrection(correction: ImageUserCorrectionEntity)

    @Query("DELETE FROM image_user_correction WHERE image_local_id = :imageLocalId")
    suspend fun deleteCorrection(imageLocalId: Long): Int

    @Upsert
    suspend fun upsertOverrides(overrides: List<UserTermOverrideEntity>)

    @Upsert
    suspend fun upsertOverride(override: UserTermOverrideEntity)

    @Query(
        """
        DELETE FROM user_term_override
        WHERE image_local_id = :imageLocalId AND kind = :kind AND normalized_key = :normalizedKey
        """,
    )
    suspend fun deleteOverride(
        imageLocalId: Long,
        kind: cn.soul2.imageai.data.db.entity.AnalysisTermKind,
        normalizedKey: String,
    ): Int

    @Query("DELETE FROM user_term_override WHERE image_local_id = :imageLocalId")
    suspend fun deleteOverrides(imageLocalId: Long): Int

    @Query("SELECT * FROM effective_image_metadata WHERE image_local_id = :imageLocalId LIMIT 1")
    suspend fun getMetadata(imageLocalId: Long): EffectiveImageMetadataEntity?

    @Query("SELECT * FROM effective_image_metadata WHERE image_local_id = :imageLocalId LIMIT 1")
    fun observeMetadata(imageLocalId: Long): Flow<EffectiveImageMetadataEntity?>

    @Query(
        """
        SELECT * FROM effective_image_term
        WHERE image_local_id = :imageLocalId
        ORDER BY kind ASC, normalized_key ASC
        """,
    )
    suspend fun getTerms(imageLocalId: Long): List<EffectiveImageTermEntity>

    @Upsert
    suspend fun upsertMetadata(metadata: EffectiveImageMetadataEntity)

    @Upsert
    suspend fun upsertTerms(terms: List<EffectiveImageTermEntity>)

    @Query("DELETE FROM effective_image_term WHERE image_local_id = :imageLocalId")
    suspend fun deleteTerms(imageLocalId: Long): Int

}
