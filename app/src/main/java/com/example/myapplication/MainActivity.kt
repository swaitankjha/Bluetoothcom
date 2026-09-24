package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.MessageEntity
import com.example.myapplication.data.PeerEntity
import com.example.myapplication.mesh.NodeIdProvider
import com.example.myapplication.mesh.PacketType
import com.example.myapplication.ui.MeshViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()

    private val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions {
            // Permissions granted
        }

        setContent {
            MyApplicationTheme {
                Surface(color = MaterialTheme.colors.background) {
                    MeshApp(viewModel)
                }
            }
        }
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

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            // Granted
        } else {
            Toast.makeText(this, "Permissions required for BLE Mesh networking.", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun MeshApp(viewModel: MeshViewModel) {
    val messages by viewModel.allMessages.collectAsState(initial = emptyList())
    val peers by viewModel.allPeers.collectAsState(initial = emptyList())
    val isBound by viewModel.isServiceBound.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Chat", "Peers", "SOS Alerts", "Status")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mesh Node: ${NodeIdProvider.getNodeId(viewModel.getApplication())}") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(if (isBound) Color.Green else Color.Red)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (isBound) "Active" else "Offline", style = MaterialTheme.typography.caption)
                    }
                }
            )
        },
        bottomBar = {
            BottomNavigation {
                tabs.forEachIndexed { index, title ->
                    BottomNavigationItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(title) },
                        icon = {
                            when (title) {
                                "Chat" -> Icon(Icons.Default.Home, "")
                                "Peers" -> Icon(Icons.Default.Person, "")
                                "SOS Alerts" -> Icon(Icons.Default.Warning, "", tint = if (selectedTab == index) Color.White else Color.Red)
                                "Status" -> Icon(Icons.Default.Info, "")
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> ChatScreen(messages, viewModel)
                1 -> PeersScreen(peers)
                2 -> SOSFeedScreen(messages, viewModel)
                3 -> NetworkStatusScreen(peers, isBound, viewModel)
            }
        }
    }
}

@Composable
fun ChatScreen(messages: List<MessageEntity>, viewModel: MeshViewModel) {
    var text by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf("broadcast") }
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(messages) { msg ->
                val isSos = msg.type == PacketType.SOS
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 2.dp,
                    backgroundColor = if (isSos) Color(0xFFFFEBEE) else MaterialTheme.colors.surface
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${if (msg.isIncoming) "From" else "To"}: ${msg.senderId}",
                                style = MaterialTheme.typography.caption,
                                color = if (isSos) Color.Red else MaterialTheme.colors.primary
                            )
                            Text(dateFormat.format(Date(msg.timestamp)), style = MaterialTheme.typography.caption)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(msg.payload, style = MaterialTheme.typography.body1)
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("TTL: ${msg.ttl}", style = MaterialTheme.typography.overline)
                            Text(msg.status.name, style = MaterialTheme.typography.overline)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = dest,
                onValueChange = { dest = it },
                label = { Text("Dest ID") },
                modifier = Modifier.width(110.dp),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            TextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (text.isNotBlank()) {
                    viewModel.sendMessage(text, dest)
                    text = ""
                }
            }) {
                Text("Send")
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PeersScreen(peers: List<PeerEntity>) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        item {
            Text("Discovered BLE Nodes", style = MaterialTheme.typography.h6, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (peers.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No nearby BLE nodes discovered yet.\nEnsure Bluetooth is ON and another device is running the app.", style = MaterialTheme.typography.body2)
                }
            }
        }
        items(peers) { peer ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), elevation = 2.dp) {
                ListItem(
                    text = { Text(peer.name ?: "Unknown Node Device") },
                    secondaryText = { Text("ID/MAC: ${peer.deviceId}\nLast seen: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(peer.lastSeen))}") },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (peer.isOnline) "Online" else "Offline", style = MaterialTheme.typography.caption)
                            Spacer(Modifier.width(6.dp))
                            Box(modifier = Modifier.size(12.dp).background(if (peer.isOnline) Color.Green else Color.Gray))
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SOSFeedScreen(messages: List<MessageEntity>, viewModel: MeshViewModel) {
    var sosMessage by remember { mutableStateOf("EMERGENCY: Need immediate assistance!") }
    val sosMessages = messages.filter { it.type == PacketType.SOS }
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = Color(0xFFFFCDD2),
            elevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red)
                    Spacer(Modifier.width(8.dp))
                    Text("Emergency SOS Broadcast", style = MaterialTheme.typography.h6, color = Color.Red)
                }
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = sosMessage,
                    onValueChange = { sosMessage = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("SOS Message") }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.sendSOS(sosMessage) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("BROADCAST SOS EMERGENCY")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Received & Sent SOS History", style = MaterialTheme.typography.subtitle1)
        Spacer(Modifier.height(4.dp))

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sosMessages) { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 2.dp,
                    backgroundColor = Color(0xFFFFEBEE)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Sender: ${msg.senderId}", style = MaterialTheme.typography.caption, color = Color.Red)
                            Text(dateFormat.format(Date(msg.timestamp)), style = MaterialTheme.typography.caption)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(msg.payload, style = MaterialTheme.typography.body1)
                        Spacer(Modifier.height(4.dp))
                        Text("Relay TTL Remaining: ${msg.ttl} hops", style = MaterialTheme.typography.overline)
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkStatusScreen(peers: List<PeerEntity>, isBound: Boolean, viewModel: MeshViewModel) {
    val onlineCount = peers.count { it.isOnline }
    val totalCount = peers.size

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mesh Network Dashboard", style = MaterialTheme.typography.h5)
        
        Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusRow("My Node ID:", NodeIdProvider.getNodeId(viewModel.getApplication()))
                StatusRow("Background Service:", if (isBound) "Running & Active" else "Stopped")
                StatusRow("Transport Protocol:", "BLE (Bluetooth Low Energy)")
                StatusRow("Max Hops (TTL):", "5 Hops (Store & Forward)")
                StatusRow("Online Direct Peers:", "$onlineCount online ($totalCount total discovered)")
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("How Mesh Relaying Works:", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(4.dp))
                Text(
                    "• Messages automatically hop between intermediary BLE devices if the destination is out of direct range.\n" +
                    "• Each relay decreases TTL by 1 (max 5 hops).\n" +
                    "• SOS emergency packets broadcast to all nodes and relay automatically.",
                    style = MaterialTheme.typography.body2
                )
            }
        }
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.body2, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.body2)
    }
}
