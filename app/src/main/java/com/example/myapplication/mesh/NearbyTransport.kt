package com.example.myapplication.mesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

@Suppress("UNUSED_PARAMETER")
class NearbyTransport(
    context: Context,
    private val nodeId: String
) : Transport {

    private val tag = "NearbyTransport"
    private val serviceId = "com.example.myapplication.mesh"
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val gson = Gson()
    private var listener: TransportListener? = null

    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    override fun startDiscovery() {
        startAdvertising()
        startDiscoveryInternal()
    }

    override fun stopDiscovery() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            connectedEndpoints.clear()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping Nearby transport", e)
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        connectionsClient.startAdvertising(
            nodeId,
            serviceId,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d(tag, "Nearby Advertising started successfully.")
        }.addOnFailureListener { e ->
            Log.e(tag, "Nearby Advertising failed", e)
        }
    }

    private fun startDiscoveryInternal() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()
        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.d(tag, "Nearby Discovery started successfully.")
        }.addOnFailureListener { e ->
            Log.e(tag, "Nearby Discovery failed", e)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(tag, "Endpoint found: $endpointId (${info.endpointName})")
            listener?.onPeerDiscovered(endpointId, info.endpointName)
            
            // Prevent connection collision race conditions by ensuring only one side initiates
            if (nodeId > endpointId) {
                connectionsClient.requestConnection(
                    nodeId,
                    endpointId,
                    connectionLifecycleCallback
                ).addOnSuccessListener {
                    Log.d(tag, "Connection requested to $endpointId")
                }.addOnFailureListener { e ->
                    Log.e(tag, "Failed to request connection to $endpointId", e)
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(tag, "Endpoint lost: $endpointId")
            connectedEndpoints.remove(endpointId)
            listener?.onPeerDisconnected(endpointId)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(tag, "Connection initiated by $endpointId (${info.endpointName})")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d(tag, "Connected to endpoint: $endpointId")
                connectedEndpoints.add(endpointId)
                listener?.onPeerConnected(endpointId)
            } else {
                Log.e(tag, "Connection failed with $endpointId: status=${result.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(tag, "Disconnected from endpoint: $endpointId")
            connectedEndpoints.remove(endpointId)
            listener?.onPeerDisconnected(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                try {
                    val json = String(bytes, StandardCharsets.UTF_8)
                    val packet = gson.fromJson(json, MeshPacket::class.java)
                    if (packet != null) {
                        Log.d(tag, "Received packet from $endpointId: ${packet.packetId}")
                        listener?.onPacketReceived(packet, endpointId)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to parse received payload", e)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Can track transfer progress if needed
        }
    }

    override fun sendPacket(packet: MeshPacket, destinationId: String) {
        try {
            val json = gson.toJson(packet)
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            val payload = Payload.fromBytes(bytes)

            if (destinationId == "broadcast") {
                if (connectedEndpoints.isEmpty()) {
                    Log.w(tag, "No connected endpoints to broadcast packet ${packet.packetId}")
                }
                connectedEndpoints.forEach { endpointId ->
                    connectionsClient.sendPayload(endpointId, payload)
                    Log.d(tag, "Sent broadcast packet ${packet.packetId} to $endpointId")
                }
            } else {
                connectionsClient.sendPayload(destinationId, payload)
                Log.d(tag, "Sent packet ${packet.packetId} to $destinationId")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to send packet via Nearby", e)
        }
    }
}
