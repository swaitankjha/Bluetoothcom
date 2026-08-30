package com.example.myapplication.mesh

import java.util.UUID

enum class PacketType {
    CHAT, SOS, ACK, PEER_DISCOVERY
}

enum class MessageStatus {
    QUEUED, FORWARDED, DELIVERED, FAILED
}

data class MeshPacket(
    val packetId: String = UUID.randomUUID().toString(),
    val senderId: String,
    val destinationId: String, // "broadcast" for SOS or discovery
    val type: PacketType,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Int = 5,
    val priority: Int = 0,
    val payload: String,
    val senderName: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null
)
