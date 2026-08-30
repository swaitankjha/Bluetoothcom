package com.example.myapplication.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.myapplication.mesh.MessageStatus
import com.example.myapplication.mesh.PacketType

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val packetId: String,
    val senderId: String,
    val senderName: String,
    val destinationId: String,
    val type: PacketType,
    val timestamp: Long,
    val payload: String,
    val status: MessageStatus,
    val ttl: Int,
    val isIncoming: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null
)
