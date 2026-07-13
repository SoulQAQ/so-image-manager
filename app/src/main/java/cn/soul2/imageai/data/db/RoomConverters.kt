package cn.soul2.imageai.data.db

import androidx.room.TypeConverter
import cn.soul2.imageai.data.db.entity.ImageAvailability

class RoomConverters {
    @TypeConverter
    fun imageAvailabilityToStorage(value: ImageAvailability): String = value.name

    @TypeConverter
    fun imageAvailabilityFromStorage(value: String): ImageAvailability =
        ImageAvailability.valueOf(value)
}
