package cn.soul2.imageai.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "provider_route",
    primaryKeys = ["partition", "provider_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProviderProfileEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["partition", "position"], unique = true), Index("provider_id")],
)
data class ProviderRouteEntity(
    val partition: ImagePartition,
    @ColumnInfo(name = "provider_id") val providerId: String,
    val position: Int,
    val enabled: Boolean,
)
