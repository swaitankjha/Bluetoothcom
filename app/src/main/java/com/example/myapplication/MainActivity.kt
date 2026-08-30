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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.MessageEntity
import com.example.myapplication.data.PeerEntity
import com.example.myapplication.mesh.NodeIdProvider
import com.example.myapplication.ui.MeshViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme

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
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions {
            // Permissions granted, Service is already started by ViewModel init
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
            // Permissions granted
        } else {
            Toast.makeText(this, "Some permissions denied. Mesh may not work correctly.", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun MeshApp(viewModel: MeshViewModel) {
    val messages by viewModel.allMessages.collectAsState(initial = emptyList())
    val peers by viewModel.allPeers.collectAsState(initial = emptyList())
    val isBound by viewModel.isServiceBound.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Mesh Chat", "Peers", "SOS")

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mesh Node: ${NodeIdProvider.getNodeId(viewModel.getApplication())}") },
                actions = {
                    if (isBound) {
                        Icon(Icons.Default.Info, contentDescription = "Active", tint = Color.Green)
                    } else {
                        Icon(Icons.Default.Info, contentDescription = "Inactive", tint = Color.Red)
                    }
                })
        },
        bottomBar = {
            BottomNavigation {
                tabs.forEachIndexed { index, title ->
                    BottomNavigationItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(title) },
                        icon = {
                            if (title == "SOS") Icon(Icons.Default.Warning, "")
                            else Icon(Icons.Default.Info, "")
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> ChatScreen(messages, viewModel)
                1 -> PeersScreen(peers)
                2 -> SOSScreen(viewModel)
            }
        }
    }
}

@Composable
fun ChatScreen(messages: List<MessageEntity>, viewModel: MeshViewModel) {
    var text by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf("broadcast") }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages.filter { it.type.name != "SOS" }) { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    elevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("${if (msg.isIncoming) "From" else "To"}: ${msg.senderId}", style = MaterialTheme.typography.caption)
                        Text(msg.payload)
                        Text(msg.status.name, style = MaterialTheme.typography.overline, modifier = Modifier.align(Alignment.End))
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(value = dest, onValueChange = { dest = it }, label = { Text("Dest ID") }, modifier = Modifier.width(100.dp))
            Spacer(Modifier.width(8.dp))
            TextField(value = text, onValueChange = { text = it }, label = { Text("Message") }, modifier = Modifier.weight(1f))
            Button(onClick = {
                viewModel.sendMessage(text, dest)
                text = ""
            }) {
                Text("Send")
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PeersScreen(peers: List<PeerEntity>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text("Nearby Nodes", style = MaterialTheme.typography.h6, modifier = Modifier.padding(8.dp))
        }
        items(peers) { peer ->
            ListItem(
                text = { Text(peer.name ?: "Unknown Node") },
                secondaryText = { Text("${peer.deviceId} - ${if (peer.isOnline) "Online" else "Last seen: " + peer.lastSeen}") },
                trailing = {
                    Box(modifier = Modifier.size(12.dp).background(if (peer.isOnline) Color.Green else Color.Gray))
                }
            )
            Divider()
        }
    }
}

@Composable
fun SOSScreen(viewModel: MeshViewModel) {
    var sosMessage by remember { mutableStateOf("EMERGENCY: Need help!") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(100.dp), tint = Color.Red)
        Spacer(Modifier.height(16.dp))
        Text("SOS BROADCAST", style = MaterialTheme.typography.h4, color = Color.Red)
        Spacer(Modifier.height(8.dp))
        Text("Your message will be relayed through all available phones in the mesh.", style = MaterialTheme.typography.body2)
        Spacer(Modifier.height(16.dp))
        TextField(value = sosMessage, onValueChange = { sosMessage = it }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { viewModel.sendSOS(sosMessage) },
            colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(60.dp)
        ) {
            Text("SEND SOS", style = MaterialTheme.typography.h5)
        }
    }
}
