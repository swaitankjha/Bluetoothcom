package com.example.myapplication.mesh

import android.Manifest
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class BluetoothClassicTransport(
    private val context: Context,
    private val scope: CoroutineScope
) : Transport {

    private val TAG = "BTTransport"
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val serverName = "MeshBTService"
    private val gson = Gson()

    private var listener: TransportListener? = null
    private val connectedSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private var serverSocket: BluetoothServerSocket? = null
    private var isListening = false
    private var isReceiverRegistered = false

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    override fun startDiscovery() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.e(TAG, "Cannot start discovery: BLUETOOTH_SCAN permission missing")
            return
        }

        startServer()

        try {
            // First check paired (bonded) devices
            if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                bluetoothAdapter?.bondedDevices?.forEach { device ->
                    Log.d(TAG, "Found paired device: ${device.name} (${device.address})")
                    listener?.onPeerDiscovered(device.address, device.name)
                }
            }

            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
            
            if (!isReceiverRegistered) {
                val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
                context.registerReceiver(receiver, filter)
                isReceiverRegistered = true
            }
            
            val started = bluetoothAdapter?.startDiscovery()
            Log.d(TAG, "Bluetooth discovery started: $started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security error during discovery", e)
        }
    }

    override fun stopDiscovery() {
        try {
            if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                bluetoothAdapter?.cancelDiscovery()
            }
            if (isReceiverRegistered) {
                context.unregisterReceiver(receiver)
                isReceiverRegistered = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
    }

    private fun startServer() {
        if (isListening || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        isListening = true
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting RFCOMM server socket...")
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(serverName, uuid)
                Log.d(TAG, "RFCOMM server socket listening.")
                while (isListening) {
                    val socket = serverSocket?.accept()
                    socket?.let { 
                        Log.d(TAG, "Accepted incoming connection from ${it.remoteDevice.address}")
                        handleNewConnection(it) 
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket error", e)
                isListening = false
            }
        }
    }

    private fun handleNewConnection(socket: BluetoothSocket) {
        val deviceId = socket.remoteDevice.address
        connectedSockets[deviceId] = socket
        Log.d(TAG, "Peer connected: $deviceId")
        listener?.onPeerConnected(deviceId)
        
        scope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                while (connectedSockets.containsKey(deviceId)) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        Log.d(TAG, "Raw line received from $deviceId: $line")
                        val packet = gson.fromJson(line, MeshPacket::class.java)
                        listener?.onPacketReceived(packet, deviceId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection lost with $deviceId", e)
            } finally {
                disconnectPeer(deviceId)
            }
        }
    }

    override fun sendPacket(packet: MeshPacket, destinationId: String) {
        val socket = connectedSockets[destinationId]
        if (socket == null || !socket.isConnected) {
            Log.d(TAG, "Not connected to $destinationId. Attempting to connect before sending...")
            connectToPeer(destinationId) {
                sendPacket(packet, destinationId)
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(packet) + "\n"
                socket.outputStream.write(json.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                Log.d(TAG, "Packet sent successfully to $destinationId (type=${packet.type}, id=${packet.packetId})")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send packet to $destinationId", e)
                disconnectPeer(destinationId)
            }
        }
    }

    fun connectToPeer(deviceId: String, onConnected: (() -> Unit)? = null) {
        if (connectedSockets.containsKey(deviceId) && connectedSockets[deviceId]?.isConnected == true) {
            onConnected?.invoke()
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(deviceId)
        if (device == null) {
            Log.e(TAG, "Cannot resolve Bluetooth device for address: $deviceId")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT permission to connect to $deviceId")
                    return@launch
                }
                Log.d(TAG, "Connecting to $deviceId...")
                val socket = device.createRfcommSocketToServiceRecord(uuid)
                bluetoothAdapter?.cancelDiscovery() // Stop scanning before connecting for better connection speed/stability
                socket.connect()
                Log.d(TAG, "Successfully connected to $deviceId")
                handleNewConnection(socket)
                withContext(Dispatchers.Main) { onConnected?.invoke() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to $deviceId", e)
            }
        }
    }

    private fun disconnectPeer(deviceId: String) {
        try {
            connectedSockets.remove(deviceId)?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket for $deviceId", e)
        }
        Log.d(TAG, "Peer disconnected: $deviceId")
        listener?.onPeerDisconnected(deviceId)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    val name = try {
                        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) it.name else null
                    } catch (e: SecurityException) {
                        null
                    }
                    Log.d(TAG, "Discovered device via BT scan: ${it.address} ($name)")
                    listener?.onPeerDiscovered(it.address, name)
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}

