package cn.soul2.imageai.data.db

import androidx.room.TypeConverter
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.CaptionCorrectionMode
import cn.soul2.imageai.data.db.entity.EffectiveCaptionSource
import cn.soul2.imageai.data.db.entity.EffectiveTermSource
import cn.soul2.imageai.data.db.entity.UserTermOverrideAction

class RoomConverters {
    @TypeConverter
    fun imageAvailabilityToStorage(value: ImageAvailability): String = value.name

    @TypeConverter
    fun imageAvailabilityFromStorage(value: String): ImageAvailability =
        ImageAvailability.valueOf(value)

    @TypeConverter
    fun analysisTermKindToStorage(value: AnalysisTermKind): String = value.name

    @TypeConverter
    fun analysisTermKindFromStorage(value: String): AnalysisTermKind =
        AnalysisTermKind.valueOf(value)

    @TypeConverter
    fun captionCorrectionModeToStorage(value: CaptionCorrectionMode): String = value.name

    @TypeConverter
    fun captionCorrectionModeFromStorage(value: String): CaptionCorrectionMode =
        CaptionCorrectionMode.valueOf(value)

    @TypeConverter
    fun userTermOverrideActionToStorage(value: UserTermOverrideAction): String = value.name

    @TypeConverter
    fun userTermOverrideActionFromStorage(value: String): UserTermOverrideAction =
        UserTermOverrideAction.valueOf(value)

    @TypeConverter
    fun effectiveCaptionSourceToStorage(value: EffectiveCaptionSource): String = value.name

    @TypeConverter
    fun effectiveCaptionSourceFromStorage(value: String): EffectiveCaptionSource =
        EffectiveCaptionSource.valueOf(value)

    @TypeConverter
    fun effectiveTermSourceToStorage(value: EffectiveTermSource): String = value.name

    @TypeConverter
    fun effectiveTermSourceFromStorage(value: String): EffectiveTermSource =
        EffectiveTermSource.valueOf(value)
}
