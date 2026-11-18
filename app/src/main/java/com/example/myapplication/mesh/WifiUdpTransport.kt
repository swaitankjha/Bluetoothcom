package com.example.myapplication.mesh

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

@Suppress("UNUSED_PARAMETER")
class WifiUdpTransport(
    context: Context,
    private val scope: CoroutineScope
) : Transport {

    private val TAG = "WifiUdpTransport"
    private val port = 8888
    private val gson = Gson()
    private var listener: TransportListener? = null
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private var receiveJob: Job? = null

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    override fun startDiscovery() {
        if (isRunning) return
        isRunning = true

        scope.launch(Dispatchers.IO) {
            try {
                socket = DatagramSocket(port).apply {
                    broadcast = true
                }
                Log.d(TAG, "WiFi UDP Socket started on port $port")

                // Start receiving loop
                receiveJob = scope.launch(Dispatchers.IO) {
                    while (isRunning && isActive) {
                        try {
                            val buf = ByteArray(4096)
                            val packet = DatagramPacket(buf, buf.size)
                            socket?.receive(packet)

                            val json = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                            val meshPacket = gson.fromJson(json, MeshPacket::class.java)
                            val senderIp = packet.address.hostAddress ?: "unknown"

                            if (meshPacket != null) {
                                Log.d(TAG, "Received UDP packet from $senderIp: ${meshPacket.packetId}")
                                withContext(Dispatchers.Main) {
                                    listener?.onPeerDiscovered(senderIp, "Node-${meshPacket.senderId}")
                                    listener?.onPacketReceived(meshPacket, senderIp)
                                }
                            }
                        } catch (e: Exception) {
                            if (isRunning) {
                                Log.e(TAG, "Error receiving UDP packet", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WiFi UDP socket", e)
            }
        }
    }

    override fun stopDiscovery() {
        isRunning = false
        receiveJob?.cancel()
        try {
            socket?.close()
            socket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing UDP socket", e)
        }
    }

    override fun sendPacket(packet: MeshPacket, destinationId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(packet)
                val bytes = json.toByteArray(StandardCharsets.UTF_8)
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val datagramPacket = DatagramPacket(bytes, bytes.size, broadcastAddress, port)

                val tempSocket = DatagramSocket().apply {
                    broadcast = true
                }
                tempSocket.send(datagramPacket)
                tempSocket.close()
                Log.d(TAG, "Successfully broadcasted packet ${packet.packetId} via WiFi UDP")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send packet via WiFi UDP", e)
            }
        }
    }
}
