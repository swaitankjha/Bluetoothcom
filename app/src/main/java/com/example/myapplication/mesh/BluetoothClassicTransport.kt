package com.example.myapplication.mesh

import android.Manifest
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    override fun startDiscovery() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        try {
            if (bluetoothAdapter?.isDiscovering == true) bluetoothAdapter.cancelDiscovery()
            
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            context.registerReceiver(receiver, filter)
            
            bluetoothAdapter?.startDiscovery()
            startServer()
        } catch (e: SecurityException) {
            Log.e(TAG, "Security error during discovery", e)
        }
    }

    override fun stopDiscovery() {
        try {
            if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                bluetoothAdapter?.cancelDiscovery()
            }
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery", e)
        }
    }

    private fun startServer() {
        if (isListening || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        isListening = true
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(serverName, uuid)
                while (isListening) {
                    val socket = serverSocket?.accept()
                    socket?.let { handleNewConnection(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
                isListening = false
            }
        }
    }

    private fun handleNewConnection(socket: BluetoothSocket) {
        val deviceId = socket.remoteDevice.address
        connectedSockets[deviceId] = socket
        listener?.onPeerConnected(deviceId)
        
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            val inputStream: InputStream = socket.inputStream
            while (connectedSockets.containsKey(deviceId)) {
                try {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val json = String(buffer, 0, bytesRead)
                        val packet = gson.fromJson(json, MeshPacket::class.java)
                        listener?.onPacketReceived(packet, deviceId)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Connection lost with $deviceId", e)
                    disconnectPeer(deviceId)
                    break
                }
            }
        }
    }

    override fun sendPacket(packet: MeshPacket, destinationId: String) {
        val socket = connectedSockets[destinationId]
        if (socket == null) {
            // If not connected, try to connect first (simplified for now)
            connectToPeer(destinationId) {
                sendPacket(packet, destinationId)
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(packet)
                socket.outputStream.write(json.toByteArray())
            } catch (e: IOException) {
                Log.e(TAG, "Send failed to $destinationId", e)
                disconnectPeer(destinationId)
            }
        }
    }

    fun connectToPeer(deviceId: String, onConnected: (() -> Unit)? = null) {
        if (connectedSockets.containsKey(deviceId)) {
            onConnected?.invoke()
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(deviceId) ?: return
        scope.launch(Dispatchers.IO) {
            try {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return@launch
                val socket = device.createRfcommSocketToServiceRecord(uuid)
                socket.connect()
                handleNewConnection(socket)
                withContext(Dispatchers.Main) { onConnected?.invoke() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to $deviceId", e)
            }
        }
    }

    private fun disconnectPeer(deviceId: String) {
        connectedSockets.remove(deviceId)?.close()
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
                    listener?.onPeerDiscovered(it.address, name)
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
