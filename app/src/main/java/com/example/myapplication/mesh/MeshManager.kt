package com.example.myapplication.mesh

import android.content.Context
import android.util.Log
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.MessageEntity
import com.example.myapplication.data.PeerEntity
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class MeshManager(
    private val context: Context,
    private val nodeId: String,
    private val scope: CoroutineScope
) : TransportListener {

    private val TAG = "MeshManager"
    private val transport: Transport = BleTransport(context, scope)
    private val database = AppDatabase.getDatabase(context)
    private val dao = database.meshDao()

    // Cache of seen packets to prevent loops
    private val seenPackets = ConcurrentHashMap.newKeySet<String>()
    
    // Routing table: destinationId -> last known peer to reach it
    private val routingTable = ConcurrentHashMap<String, String>()

    init {
        transport.setListener(this)
        startStoreAndForwardJob()
    }

    private fun startStoreAndForwardJob() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30000) // Every 30 seconds
                val queuedMessages = dao.getQueuedMessages()
                queuedMessages.forEach { msg ->
                    val packet = MeshPacket(
                        packetId = msg.packetId,
                        senderId = msg.senderId,
                        destinationId = msg.destinationId,
                        type = msg.type,
                        timestamp = msg.timestamp,
                        ttl = msg.ttl,
                        payload = msg.payload,
                        senderName = msg.senderName,
                        latitude = msg.latitude,
                        longitude = msg.longitude
                    )
                    attemptSend(packet)
                }
            }
        }
    }

    fun start() {
        transport.startDiscovery()
    }

    fun stop() {
        transport.stopDiscovery()
    }

    fun sendMessage(payload: String, destinationId: String, type: PacketType = PacketType.CHAT) {
        val packet = MeshPacket(
            senderId = nodeId,
            destinationId = destinationId,
            type = type,
            payload = payload
        )
        saveMessage(packet, isIncoming = false)
        seenPackets.add(packet.packetId)
        attemptSend(packet)
    }

    fun sendSOS(payload: String, latitude: Double? = null, longitude: Double? = null) {
        val packet = MeshPacket(
            senderId = nodeId,
            destinationId = "broadcast",
            type = PacketType.SOS,
            payload = payload,
            latitude = latitude,
            longitude = longitude
        )
        saveMessage(packet, isIncoming = false)
        seenPackets.add(packet.packetId)
        attemptSend(packet)
    }

    private fun attemptSend(packet: MeshPacket) {
        if (packet.destinationId == "broadcast") {
            broadcastToNeighbors(packet)
        } else {
            val nextHop = routingTable[packet.destinationId]
            if (nextHop != null) {
                transport.sendPacket(packet, nextHop)
            } else {
                broadcastToNeighbors(packet)
            }
        }
    }

    private fun broadcastToNeighbors(packet: MeshPacket) {
        scope.launch(Dispatchers.IO) {
            val onlinePeers = dao.getOnlinePeers()
            onlinePeers.forEach { peer ->
                // Don't send back to the node that just sent it to us if we know it
                transport.sendPacket(packet, peer.deviceId)
            }
        }
    }

    override fun onPacketReceived(packet: MeshPacket, fromDeviceId: String) {
        if (seenPackets.contains(packet.packetId)) return
        seenPackets.add(packet.packetId)

        // Update routing table: senderId can be reached via fromDeviceId
        routingTable[packet.senderId] = fromDeviceId

        when {
            packet.destinationId == nodeId -> {
                if (packet.type == PacketType.ACK) {
                    updateMessageStatus(packet.payload, MessageStatus.DELIVERED)
                } else {
                    saveMessage(packet, isIncoming = true)
                    sendAck(packet)
                }
            }
            packet.destinationId == "broadcast" -> {
                saveMessage(packet, isIncoming = true)
                if (packet.type == PacketType.SOS) {
                    relayPacket(packet)
                }
            }
            else -> {
                relayPacket(packet)
            }
        }
    }

    private fun sendAck(originalPacket: MeshPacket) {
        val ackPacket = MeshPacket(
            senderId = nodeId,
            destinationId = originalPacket.senderId,
            type = PacketType.ACK,
            payload = originalPacket.packetId // ACK payload is the original packetId
        )
        attemptSend(ackPacket)
    }

    private fun relayPacket(packet: MeshPacket) {
        if (packet.ttl <= 1) return
        val relayedPacket = packet.copy(ttl = packet.ttl - 1)
        attemptSend(relayedPacket)
    }

    override fun onPeerDiscovered(deviceId: String, name: String?) {
        scope.launch(Dispatchers.IO) {
            dao.insertPeer(PeerEntity(deviceId, name, System.currentTimeMillis()))
            (transport as? BluetoothClassicTransport)?.connectToPeer(deviceId)
        }
    }

    override fun onPeerConnected(deviceId: String) {
        scope.launch(Dispatchers.IO) {
            dao.updatePeerStatus(deviceId, true)
        }
    }

    override fun onPeerDisconnected(deviceId: String) {
        scope.launch(Dispatchers.IO) {
            dao.updatePeerStatus(deviceId, false)
        }
    }

    private fun saveMessage(packet: MeshPacket, isIncoming: Boolean) {
        scope.launch(Dispatchers.IO) {
            dao.insertMessage(MessageEntity(
                packetId = packet.packetId,
                senderId = packet.senderId,
                senderName = packet.senderName,
                destinationId = packet.destinationId,
                type = packet.type,
                timestamp = packet.timestamp,
                payload = packet.payload,
                status = if (isIncoming) MessageStatus.DELIVERED else MessageStatus.QUEUED,
                ttl = packet.ttl,
                isIncoming = isIncoming,
                latitude = packet.latitude,
                longitude = packet.longitude
            ))
        }
    }

    private fun updateMessageStatus(packetId: String, status: MessageStatus) {
        scope.launch(Dispatchers.IO) {
            dao.updateMessageStatus(packetId, status.name)
        }
    }
}
