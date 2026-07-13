package cn.soul2.imageai.data.db

import android.content.Context
import androidx.room.Room

object AppDatabaseFactory {
    private const val DATABASE_NAME = "so_image_manager.db"

    fun create(context: Context): AppDatabase = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        DATABASE_NAME,
    ).build()
}
