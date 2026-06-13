package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")

class MainActivity : ComponentActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null

    private var connectedSocket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null

    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val discoveredDevices = mutableStateListOf<String>()
    private val deviceObjects = mutableListOf<BluetoothDevice>()

    private val appUUID =
        UUID.fromString(
            "12345678-1234-1234-1234-123456789abc"
        )

    private val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager =
            getSystemService(
                Context.BLUETOOTH_SERVICE
            ) as BluetoothManager

        bluetoothAdapter =
            bluetoothManager.adapter

        if (bluetoothAdapter == null) {

            Toast.makeText(
                this,
                "Bluetooth Not Supported",
                Toast.LENGTH_SHORT
            ).show()

            finish()
        }

        if (bluetoothAdapter?.isEnabled == false) {

            val enableIntent =
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

            startActivity(enableIntent)
        }

        val filter =
            IntentFilter(BluetoothDevice.ACTION_FOUND)

        registerReceiver(receiver, filter)

        setContent {
            BluetoothUI()
        }
    }

    private fun hasPermissions(): Boolean {

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&

                    checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED

        } else {

            true
        }
    }

    private fun requestBluetoothPermissions() {

        requestPermissions(
            permissions,
            100
        )
    }

    private fun sendToRender(message: String) {

        lifecycleScope.launch(Dispatchers.IO) {

            try {

                val client = OkHttpClient()

                val json = """
                    {
                        "message":"$message"
                    }
                """.trimIndent()

                val body = json.toRequestBody(
                    "application/json".toMediaType()
                )

                val request = Request.Builder()
                    .url("https://bluetooth-server-1.onrender.com/message")
                    .post(body)
                    .build()

                val response =
                    client.newCall(request).execute()

                withContext(Dispatchers.Main) {

                    if (response.isSuccessful) {

                        Toast.makeText(
                            this@MainActivity,
                            "Message Sent To Cloud ☁",
                            Toast.LENGTH_SHORT
                        ).show()

                    } else {

                        Toast.makeText(
                            this@MainActivity,
                            "Server Error ${response.code}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {

                withContext(Dispatchers.Main) {

                    Toast.makeText(
                        this@MainActivity,
                        "ERROR: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {

            if (intent?.action ==
                BluetoothDevice.ACTION_FOUND
            ) {

                val device =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )

                    } else {

                        @Suppress("DEPRECATION")

                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE
                        )
                    }

                device?.let {

                    val name =
                        it.name ?: "Unknown Device"

                    if (!discoveredDevices.contains(name)) {

                        discoveredDevices.add(name)
                        deviceObjects.add(it)
                    }
                }
            }
        }
    }

    private fun startListening(
        chatMessages: MutableList<String>
    ) {

        lifecycleScope.launch(Dispatchers.IO) {

            val buffer = ByteArray(1024)

            while (true) {

                try {

                    val bytes =
                        inputStream?.read(buffer) ?: 0

                    if (bytes > 0) {

                        val incomingMessage =
                            String(buffer, 0, bytes)

                        withContext(Dispatchers.Main) {

                            chatMessages.add(
                                "Friend: $incomingMessage"
                            )
                        }
                    }

                } catch (_: Exception) {

                    break
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }

        try {
            connectedSocket?.close()
        } catch (_: Exception) {
        }

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    @Composable
    fun BluetoothUI() {

        var message by remember {
            mutableStateOf("")
        }

        val chatMessages = remember {
            mutableStateListOf<String>()
        }

        var connectedDevice by remember {
            mutableStateOf("🔴 Not Connected")
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A))
                .padding(16.dp)
        ) {

            Text(
                text = "Hybrid Chat",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = connectedDevice,
                color = Color.LightGray
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {

                Button(
                    onClick = {

                        if (!hasPermissions()) {

                            requestBluetoothPermissions()

                        } else {

                            discoveredDevices.clear()
                            deviceObjects.clear()

                            try {

                                bluetoothAdapter?.cancelDiscovery()
                                bluetoothAdapter?.startDiscovery()

                                Toast.makeText(
                                    this@MainActivity,
                                    "Scanning Devices...",
                                    Toast.LENGTH_SHORT
                                ).show()

                            } catch (_: Exception) {

                                Toast.makeText(
                                    this@MainActivity,
                                    "Scan Failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },

                    shape = RoundedCornerShape(14.dp),

                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor =
                                Color(0xFF2563EB)
                        )
                ) {

                    Text(
                        "📡 Scan",
                        color = Color.White
                    )
                }

                Button(
                    onClick = {

                        lifecycleScope.launch(
                            Dispatchers.IO
                        ) {

                            try {

                                serverSocket =
                                    bluetoothAdapter
                                        ?.listenUsingRfcommWithServiceRecord(
                                            "BluetoothChat",
                                            appUUID
                                        )

                                withContext(
                                    Dispatchers.Main
                                ) {

                                    Toast.makeText(
                                        this@MainActivity,
                                        "Waiting For Connection...",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }

                                val socket =
                                    serverSocket?.accept()

                                connectedSocket =
                                    socket

                                inputStream =
                                    socket?.inputStream

                                outputStream =
                                    socket?.outputStream

                                withContext(
                                    Dispatchers.Main
                                ) {

                                    connectedDevice =
                                        "🟢 Client Connected"

                                    chatMessages.add(
                                        "Client Connected"
                                    )
                                }

                                startListening(
                                    chatMessages
                                )

                            } catch (_: Exception) {

                                withContext(
                                    Dispatchers.Main
                                ) {

                                    Toast.makeText(
                                        this@MainActivity,
                                        "Hosting Failed",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },

                    shape =
                        RoundedCornerShape(14.dp),

                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor =
                                Color(0xFF059669)
                        )
                ) {

                    Text(
                        "Host",
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Nearby Devices",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {

                itemsIndexed(
                    discoveredDevices
                ) { index, deviceName ->

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),

                        shape =
                            RoundedCornerShape(18.dp),

                        backgroundColor =
                            Color(0xFF1E293B)
                    ) {

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),

                            horizontalArrangement =
                                Arrangement.SpaceBetween,

                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {

                            Column {

                                Text(
                                    text = deviceName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(
                                    modifier =
                                        Modifier.height(4.dp)
                                )

                                Text(
                                    text =
                                        deviceObjects[index]
                                            .address,

                                    color = Color.Gray,

                                    fontSize = 12.sp
                                )
                            }

                            Button(
                                onClick = {

                                    lifecycleScope.launch(
                                        Dispatchers.IO
                                    ) {

                                        try {

                                            bluetoothAdapter
                                                ?.cancelDiscovery()

                                            val socket =
                                                deviceObjects[index]
                                                    .createRfcommSocketToServiceRecord(
                                                        appUUID
                                                    )

                                            socket.connect()

                                            connectedSocket =
                                                socket

                                            inputStream =
                                                socket.inputStream

                                            outputStream =
                                                socket.outputStream

                                            withContext(
                                                Dispatchers.Main
                                            ) {

                                                connectedDevice =
                                                    "🟢 Connected to $deviceName"

                                                chatMessages.add(
                                                    "Connected to $deviceName"
                                                )
                                            }

                                            startListening(
                                                chatMessages
                                            )

                                        } catch (_: Exception) {

                                            withContext(
                                                Dispatchers.Main
                                            ) {

                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Connection Failed",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                },

                                colors =
                                    ButtonDefaults.buttonColors(
                                        backgroundColor =
                                            Color(0xFF7C3AED)
                                    )
                            ) {

                                Text(
                                    "Connect",
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {

                itemsIndexed(
                    chatMessages
                ) { _, msg ->

                    val isMine =
                        msg.startsWith("Me:")

                    Row(
                        modifier =
                            Modifier.fillMaxWidth(),

                        horizontalArrangement =
                            if (isMine)
                                Arrangement.End
                            else
                                Arrangement.Start
                    ) {

                        Card(
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .widthIn(max = 280.dp),

                            shape =
                                RoundedCornerShape(18.dp),

                            backgroundColor =
                                if (isMine)
                                    Color(0xFF2563EB)
                                else
                                    Color(0xFF1E293B)
                        ) {

                            Text(
                                text = msg,
                                color = Color.White,
                                modifier =
                                    Modifier.padding(14.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                TextField(
                    value = message,

                    onValueChange = {
                        message = it
                    },

                    modifier =
                        Modifier.weight(1f),

                    colors =
                        TextFieldDefaults.textFieldColors(
                            backgroundColor =
                                Color(0xFF1E293B),

                            textColor =
                                Color.White
                        ),

                    placeholder = {

                        Text(
                            "Type Message...",
                            color = Color.Gray
                        )
                    }
                )

                Spacer(
                    modifier = Modifier.width(8.dp)
                )

                FloatingActionButton(
                    onClick = {

                        if (message.isNotEmpty()) {

                            if (connectedSocket != null) {

                                try {

                                    outputStream?.write(
                                        message.toByteArray()
                                    )

                                    chatMessages.add(
                                        "Me: $message"
                                    )

                                } catch (_: Exception) {

                                    chatMessages.add(
                                        "Bluetooth Send Failed"
                                    )
                                }

                            } else {

                                sendToRender(message)

                                chatMessages.add(
                                    "☁ Cloud: $message"
                                )
                            }

                            message = ""
                        }
                    },

                    backgroundColor =
                        Color(0xFF2563EB)
                ) {

                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }
        }
    }
}