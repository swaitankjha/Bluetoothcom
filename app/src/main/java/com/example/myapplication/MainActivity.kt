package com.example.myapplication

import android.Manifest
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MainActivity : ComponentActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val serverName = "MyBTService"

    private val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }.toTypedArray()

    private var connectedSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var listenJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            finish()
        }

        setContent { BTCommUI() }
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun checkPermissions(onGranted: () -> Unit) {
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        } else {
            onGranted()
        }
    }

    private fun startDiscovery() {
        if (!hasBluetoothScanPermission() || bluetoothAdapter == null) return

        try {
            bluetoothAdapter?.cancelDiscovery()

            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            registerReceiver(receiver, filter)

            // SENSITIVE CALL: Handled by surrounding try-catch and hasBluetoothScanPermission() check
            bluetoothAdapter?.startDiscovery()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Bluetooth discovery permission error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Server-side function to start listening for incoming connections.
     */
    private fun startAcceptingConnections(onSuccess: (BluetoothSocket) -> Unit, onFail: (String) -> Unit) {
        if (!hasBluetoothConnectPermission() || bluetoothAdapter == null) {
            onFail("Error: BLUETOOTH_CONNECT permission missing.")
            return
        }

        listenJob?.cancel()

        try {
            // SENSITIVE CALL: Handled by surrounding try-catch and hasBluetoothConnectPermission() check
            serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(serverName, uuid)

        } catch (e: SecurityException) {
            onFail("Security error setting up server: ${e.message}")
            return
        } catch (e: Exception) {
            onFail("Error setting up server: ${e.message}")
            return
        }

        listenJob = lifecycleScope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                // SENSITIVE CALL: Handled by surrounding try-catch and hasBluetoothConnectPermission() check
                socket = serverSocket?.accept()

                if (socket != null) {
                    serverSocket?.close()
                    withContext(Dispatchers.Main) { onSuccess(socket) }
                }

            } catch (e: Exception) {
                // If the error is not due to the socket being closed/cancelled, report it.
                if (e !is java.io.IOException || e.message?.contains("socket closed") == false) {
                    withContext(Dispatchers.Main) { onFail("Server failed: ${e.message}") }
                }
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                if (!hasBluetoothScanPermission()) return

                try {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        // device.name also requires BLUETOOTH_CONNECT on API 31+
                        val deviceName = try { it.name } catch (_: SecurityException) { null }
                        if (deviceName != null && !discoveredDevices.contains(it)) {
                            discoveredDevices.add(it)
                        }
                    }
                } catch (_: SecurityException) {}
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        try { listenJob?.cancel() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { connectedSocket?.close() } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            // Permissions granted
        } else {
            Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    fun BTCommUI() {
        var message by remember { mutableStateOf("") }
        val chatMessages = remember { mutableStateListOf<String>() }
        val scope = rememberCoroutineScope()
        var isConnected by remember { mutableStateOf(false) }
        var isListening by remember { mutableStateOf(false) }

        val setupConnection: (BluetoothSocket, String) -> Unit = { socket, name ->
            try { connectedSocket?.close() } catch (_: Exception) {}
            try { serverSocket?.close() } catch (_: Exception) {}
            listenJob?.cancel()

            connectedSocket = socket
            outputStream = socket.outputStream
            inputStream = socket.inputStream
            isConnected = true
            isListening = false
            chatMessages.clear()
            chatMessages.add("Connection established with $name.")

            scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(1024)
                while (true) {
                    try {
                        val bytesRead = inputStream?.read(buffer) ?: 0
                        if (bytesRead > 0) {
                            val msg = String(buffer, 0, bytesRead)
                            withContext(Dispatchers.Main) {
                                chatMessages.add("$name: $msg")
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            chatMessages.add("Connection lost: ${e.message}")
                            isConnected = false
                        }
                        break
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { checkPermissions { startDiscovery() } }) {
                    Text("1. Scan & Connect (Client)")
                }

                Button(
                    onClick = {
                        checkPermissions {
                            if (isConnected) {
                                chatMessages.add("Disconnect before listening.")
                                return@checkPermissions
                            }
                            isListening = true
                            chatMessages.add("Started listening for connections...")
                            startAcceptingConnections(
                                onSuccess = { socket ->
                                    setupConnection(socket, "Remote Device")
                                },
                                onFail = { errorMsg ->
                                    isListening = false
                                    chatMessages.add(errorMsg)
                                }
                            )
                        }
                    },
                    enabled = !isListening && !isConnected
                ) {
                    Text(if (isListening) "Listening..." else "2. Wait for Connection (Server)")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            if (!isConnected && !isListening) {
                Text("Discovered Devices (Select to Connect):", style = MaterialTheme.typography.h6)
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(discoveredDevices) { device ->
                        Button(onClick = {
                            scope.launch(Dispatchers.IO) {

                                // Explicit permission check right here
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                    withContext(Dispatchers.Main) {
                                        chatMessages.add("Error: BLUETOOTH_CONNECT permission missing.")
                                    }
                                    return@launch
                                }

                                // Cancel discovery before connecting
                                bluetoothAdapter?.cancelDiscovery()

                                try {
                                    // Explicitly handle SecurityException
                                    val socket: BluetoothSocket? = try {
                                        device.createRfcommSocketToServiceRecord(uuid)
                                    } catch (se: SecurityException) {
                                        withContext(Dispatchers.Main) {
                                            chatMessages.add("Security exception creating socket: ${se.message}")
                                        }
                                        null
                                    }

                                    val name: String = try {
                                        device.name ?: device.address
                                    } catch (se: SecurityException) {
                                        device.address
                                    }

                                    if (socket != null) {
                                        try {
                                            socket.connect()
                                            withContext(Dispatchers.Main) { setupConnection(socket, name) }
                                        } catch (se: SecurityException) {
                                            withContext(Dispatchers.Main) {
                                                chatMessages.add("Security exception connecting: ${se.message}")
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                chatMessages.add("Connection failed: ${e.message}")
                                            }
                                        }
                                    }

                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        chatMessages.add("Unexpected error: ${e.message}")
                                    }
                                }
                            }
                        }) {
                            // Also check permission before accessing name
                            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                device.address
                            } else {
                                try { device.name ?: device.address } catch (se: SecurityException) { device.address }
                            }
                            Text(name)
                        }
                    }


                }
            }

            if (isConnected || isListening) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(chatMessages) { msg ->
                        Text(msg)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }

            if (isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    TextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type message...") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                outputStream?.write(message.toByteArray())
                                withContext(Dispatchers.Main) {
                                    chatMessages.add("Me: $message")
                                    message = ""
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    chatMessages.add("Send failed. Connection broken: ${e.message}")
                                    isConnected = false
                                }
                            }
                        }
                    }) {
                        Text("Send")
                    }
                }
            }
        }
    }
}