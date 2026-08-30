package com.example.myapplication.data

import android.content.Context
import androidx.room.*
import com.example.myapplication.mesh.MessageStatus
import com.example.myapplication.mesh.PacketType

@Database(entities = [MessageEntity::class, PeerEntity::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun meshDao(): MeshDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mesh_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromPacketType(value: PacketType) = value.name

    @TypeConverter
    fun toPacketType(value: String) = PacketType.valueOf(value)

    @TypeConverter
    fun fromMessageStatus(value: MessageStatus) = value.name

    @TypeConverter
    fun toMessageStatus(value: String) = MessageStatus.valueOf(value)
}
