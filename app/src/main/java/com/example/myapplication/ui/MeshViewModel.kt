package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.mesh.MeshManager
import com.example.myapplication.mesh.NodeIdProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MeshViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getDatabase(application).meshDao()
    
    val allMessages = dao.getAllMessages()
    val allPeers = dao.getAllPeers()

    private val _isServiceBound = MutableStateFlow(true)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private val meshManager: MeshManager

    init {
        val nodeId = NodeIdProvider.getNodeId(application)
        meshManager = MeshManager(application, nodeId, viewModelScope)
        meshManager.start()
    }

    fun sendMessage(payload: String, destinationId: String) {
        meshManager.sendMessage(payload, destinationId)
    }

    fun sendSOS(payload: String) {
        meshManager.sendSOS(payload)
    }

    override fun onCleared() {
        super.onCleared()
        meshManager.stop()
    }
}
