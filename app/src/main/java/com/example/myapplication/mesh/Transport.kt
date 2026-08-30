package com.example.myapplication.mesh

interface Transport {
    fun startDiscovery()
    fun stopDiscovery()
    fun sendPacket(packet: MeshPacket, destinationId: String)
    fun setListener(listener: TransportListener)
}

interface TransportListener {
    fun onPacketReceived(packet: MeshPacket, fromDeviceId: String)
    fun onPeerDiscovered(deviceId: String, name: String?)
    fun onPeerConnected(deviceId: String)
    fun onPeerDisconnected(deviceId: String)
}
