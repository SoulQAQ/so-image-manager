package cn.soul2.imageai.data.db.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import cn.soul2.imageai.data.db.entity.ActiveImageAnalysisEntity
import cn.soul2.imageai.data.db.entity.AnalysisActivationDiagnosticEntity
import cn.soul2.imageai.data.db.entity.AnalysisTermEntity
import cn.soul2.imageai.data.db.entity.ImageAnalysisEntity
import cn.soul2.imageai.analysis.AnalysisRetentionCandidate
import kotlinx.coroutines.flow.Flow

internal data class AnalysisHistoryRow(
    @Embedded val analysis: ImageAnalysisEntity,
    @ColumnInfo(name = "diagnostic_code") val diagnosticCode: String?,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
)

@Dao
internal interface AnalysisDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAnalysis(analysis: ImageAnalysisEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTerms(terms: List<AnalysisTermEntity>)

    @Query("SELECT * FROM image_analysis WHERE analysis_id = :analysisId LIMIT 1")
    suspend fun getAnalysis(analysisId: String): ImageAnalysisEntity?

    @Query(
        """
        SELECT * FROM analysis_term
        WHERE analysis_id = :analysisId
        ORDER BY kind ASC, normalized_key ASC
        """,
    )
    suspend fun getTerms(analysisId: String): List<AnalysisTermEntity>

    @Query("SELECT COUNT(*) FROM image_analysis WHERE image_local_id = :imageLocalId")
    suspend fun countAnalyses(imageLocalId: Long): Int

    @Query("SELECT COUNT(*) FROM image_analysis WHERE completed_at_epoch_millis >= :dayStartEpochMillis")
    suspend fun countCompletedSince(dayStartEpochMillis: Long): Int

    @Query("SELECT * FROM active_image_analysis WHERE image_local_id = :imageLocalId LIMIT 1")
    suspend fun getActive(imageLocalId: Long): ActiveImageAnalysisEntity?

    @Upsert
    suspend fun upsertActive(active: ActiveImageAnalysisEntity)

    @Query(
        """
        SELECT analysis.* FROM image_analysis AS analysis
        INNER JOIN active_image_analysis AS active
            ON active.analysis_id = analysis.analysis_id
        WHERE active.image_local_id = :imageLocalId
        LIMIT 1
        """,
    )
    suspend fun getActiveAnalysis(imageLocalId: Long): ImageAnalysisEntity?

    @Query(
        """
        SELECT analysis.*, diagnostic.code AS diagnostic_code,
            CASE WHEN active.analysis_id IS NULL THEN 0 ELSE 1 END AS is_active
        FROM image_analysis AS analysis
        LEFT JOIN active_image_analysis AS active
            ON active.analysis_id = analysis.analysis_id
        LEFT JOIN analysis_activation_diagnostic AS diagnostic
            ON diagnostic.analysis_id = analysis.analysis_id
        WHERE analysis.image_local_id = :imageLocalId
        ORDER BY analysis.completed_at_epoch_millis DESC, analysis.analysis_id DESC
        """,
    )
    fun observeHistory(imageLocalId: Long): Flow<List<AnalysisHistoryRow>>

    @Query(
        """
        SELECT analysis.*, diagnostic.code AS diagnostic_code,
            CASE WHEN active.analysis_id IS NULL THEN 0 ELSE 1 END AS is_active
        FROM image_analysis AS analysis
        LEFT JOIN active_image_analysis AS active
            ON active.analysis_id = analysis.analysis_id
        LEFT JOIN analysis_activation_diagnostic AS diagnostic
            ON diagnostic.analysis_id = analysis.analysis_id
        WHERE analysis.image_local_id = :imageLocalId
        ORDER BY analysis.completed_at_epoch_millis DESC, analysis.analysis_id DESC
        """,
    )
    suspend fun getHistory(imageLocalId: Long): List<AnalysisHistoryRow>

    @Upsert
    suspend fun upsertDiagnostic(diagnostic: AnalysisActivationDiagnosticEntity)

    @Query(
        "SELECT * FROM analysis_activation_diagnostic WHERE analysis_id = :analysisId LIMIT 1",
    )
    suspend fun getDiagnostic(analysisId: String): AnalysisActivationDiagnosticEntity?

    @Query("DELETE FROM analysis_activation_diagnostic WHERE analysis_id = :analysisId")
    suspend fun deleteDiagnostic(analysisId: String): Int

    @Query(
        """
        SELECT analysis.analysis_id AS analysisId,
            analysis.image_local_id AS imageLocalId,
            analysis.completed_at_epoch_millis AS completedAtEpochMillis,
            (
                LENGTH(CAST(analysis.analysis_id AS BLOB)) * 3 +
                LENGTH(CAST(analysis.caption AS BLOB)) +
                COALESCE(LENGTH(CAST(analysis.extension_json AS BLOB)), 0) +
                LENGTH(CAST(analysis.content_hash AS BLOB)) +
                LENGTH(CAST(analysis.provider_profile_id AS BLOB)) +
                LENGTH(CAST(analysis.model_profile_id AS BLOB)) +
                LENGTH(CAST(analysis.protocol_definition_id AS BLOB)) +
                LENGTH(CAST(analysis.prompt_template_id AS BLOB)) +
                COALESCE((
                    SELECT SUM(
                        LENGTH(CAST(term.analysis_id AS BLOB)) * 3 +
                        LENGTH(CAST(term.kind AS BLOB)) * 2 +
                        LENGTH(CAST(term.normalized_key AS BLOB)) * 2 +
                        LENGTH(CAST(term.display_value AS BLOB)) + 192
                    )
                    FROM analysis_term AS term
                    WHERE term.analysis_id = analysis.analysis_id
                ), 0) +
                COALESCE((
                    SELECT
                        LENGTH(CAST(diagnostic.analysis_id AS BLOB)) * 2 +
                        LENGTH(CAST(diagnostic.code AS BLOB)) +
                        COALESCE(LENGTH(CAST(diagnostic.detail AS BLOB)), 0) + 256
                    FROM analysis_activation_diagnostic AS diagnostic
                    WHERE diagnostic.analysis_id = analysis.analysis_id
                ), 0) + 768
            ) AS estimatedBytes,
            EXISTS(
                SELECT 1 FROM analysis_activation_diagnostic AS diagnostic
                WHERE diagnostic.analysis_id = analysis.analysis_id
            ) AS hasDiagnostic
        FROM image_analysis AS analysis
        WHERE NOT EXISTS(
            SELECT 1 FROM active_image_analysis AS active
            WHERE active.analysis_id = analysis.analysis_id
        )
        ORDER BY analysis.completed_at_epoch_millis ASC, analysis.analysis_id ASC
        """,
    )
    suspend fun getInactiveRetentionCandidates(): List<AnalysisRetentionCandidate>

    @Query(
        """
        DELETE FROM image_analysis
        WHERE analysis_id = :analysisId
          AND NOT EXISTS(
              SELECT 1 FROM active_image_analysis
              WHERE active_image_analysis.analysis_id = image_analysis.analysis_id
          )
        """,
    )
    suspend fun deleteInactiveAnalysis(analysisId: String): Int
}
