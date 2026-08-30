package com.example.myapplication.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.MessageEntity
import com.example.myapplication.data.PeerEntity
import com.example.myapplication.mesh.PacketType
import com.example.myapplication.service.MeshService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MeshViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getDatabase(application).meshDao()
    
    val allMessages = dao.getAllMessages()
    val allPeers = dao.getAllPeers()

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private var meshService: MeshService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MeshService.MeshBinder
            meshService = binder.getService()
            _isServiceBound.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            _isServiceBound.value = false
        }
    }

    init {
        bindService()
    }

    private fun bindService() {
        val intent = Intent(getApplication(), MeshService::class.java)
        getApplication<Application>().startForegroundService(intent)
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun sendMessage(payload: String, destinationId: String) {
        meshService?.getMeshManager()?.sendMessage(payload, destinationId)
    }

    fun sendSOS(payload: String) {
        meshService?.getMeshManager()?.sendSOS(payload)
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unbindService(serviceConnection)
    }
}
