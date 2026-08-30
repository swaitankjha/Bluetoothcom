package com.example.myapplication.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String, // MAC address or unique ID
    val name: String?,
    val lastSeen: Long,
    val isOnline: Boolean = false,
    val connectionType: String = "BT_CLASSIC"
)
