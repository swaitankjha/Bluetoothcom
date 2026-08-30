package com.example.myapplication.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MeshDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM peers WHERE isOnline = 1")
    suspend fun getOnlinePeers(): List<PeerEntity>

    @Query("SELECT * FROM messages WHERE status = 'QUEUED' AND isIncoming = 0")
    suspend fun getQueuedMessages(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE packetId = :packetId")
    suspend fun updateMessageStatus(packetId: String, status: String)

    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: PeerEntity)

    @Query("UPDATE peers SET isOnline = :isOnline WHERE deviceId = :deviceId")
    suspend fun updatePeerStatus(deviceId: String, isOnline: Boolean)
}
