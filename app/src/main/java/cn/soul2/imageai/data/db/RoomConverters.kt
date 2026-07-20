package cn.soul2.imageai.data.db

import androidx.room.TypeConverter
import cn.soul2.imageai.data.db.entity.ImageAvailability
import cn.soul2.imageai.data.db.entity.ImageSource
import cn.soul2.imageai.data.db.entity.AnalysisTermKind
import cn.soul2.imageai.data.db.entity.CaptionCorrectionMode
import cn.soul2.imageai.data.db.entity.EffectiveCaptionSource
import cn.soul2.imageai.data.db.entity.EffectiveTermSource
import cn.soul2.imageai.data.db.entity.UserTermOverrideAction
import cn.soul2.imageai.data.db.entity.SearchGramOwnerType
import cn.soul2.imageai.data.db.entity.SearchTermOwnership
import cn.soul2.imageai.data.db.entity.SearchTermUnitType
import cn.soul2.imageai.search.PinyinAliasType
import cn.soul2.imageai.data.db.entity.ModelProtocolType
import cn.soul2.imageai.data.db.entity.ProviderAuthMode

class RoomConverters {
    @TypeConverter
    fun imageAvailabilityToStorage(value: ImageAvailability): String = value.name

    @TypeConverter
    fun imageAvailabilityFromStorage(value: String): ImageAvailability =
        ImageAvailability.valueOf(value)

    @TypeConverter
    fun imageSourceToStorage(value: ImageSource): String = value.name

    @TypeConverter
    fun imageSourceFromStorage(value: String): ImageSource = ImageSource.valueOf(value)

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

    @TypeConverter
    fun searchTermUnitTypeToStorage(value: SearchTermUnitType): String = value.name

    @TypeConverter
    fun searchTermUnitTypeFromStorage(value: String): SearchTermUnitType =
        SearchTermUnitType.valueOf(value)

    @TypeConverter
    fun searchTermOwnershipToStorage(value: SearchTermOwnership): String = value.name

    @TypeConverter
    fun searchTermOwnershipFromStorage(value: String): SearchTermOwnership =
        SearchTermOwnership.valueOf(value)

    @TypeConverter
    fun pinyinAliasTypeToStorage(value: PinyinAliasType): String = value.name

    @TypeConverter
    fun pinyinAliasTypeFromStorage(value: String): PinyinAliasType =
        PinyinAliasType.valueOf(value)

    @TypeConverter
    fun searchGramOwnerTypeToStorage(value: SearchGramOwnerType): String = value.name

    @TypeConverter
    fun searchGramOwnerTypeFromStorage(value: String): SearchGramOwnerType =
        SearchGramOwnerType.valueOf(value)

    @TypeConverter
    fun providerAuthModeToStorage(value: ProviderAuthMode): String = value.name

    @TypeConverter
    fun providerAuthModeFromStorage(value: String): ProviderAuthMode =
        ProviderAuthMode.valueOf(value)

    @TypeConverter
    fun modelProtocolTypeToStorage(value: ModelProtocolType): String = value.name

    @TypeConverter
    fun modelProtocolTypeFromStorage(value: String): ModelProtocolType =
        ModelProtocolType.valueOf(value)
}
