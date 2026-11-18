package com.example.myapplication.mesh

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class BleTransport(
    private val context: Context,
    private val scope: CoroutineScope
) : Transport {

    private val TAG = "BleTransport"
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val bluetoothLeAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    private val gson = Gson()

    private var listener: TransportListener? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var isDiscovering = false

    private val seenBroadcastPackets = ConcurrentHashMap.newKeySet<String>()

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7b420001-3456-7890-abcd-ef0123456789")
        const val MANUFACTURER_ID = 0x5555
    }

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    override fun startDiscovery() {
        if (isDiscovering) return
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) || !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            Log.e(TAG, "Missing BLE permissions")
            return
        }

        isDiscovering = true
        startAdvertising(null)
        startScanning()
    }

    override fun stopDiscovery() {
        isDiscovering = false
        try {
            if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                scanCallback?.let { bluetoothLeScanner?.stopScan(it) }
            }
            if (hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                advertiseCallback?.let { bluetoothLeAdvertiser?.stopAdvertising(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE transport", e)
        }
    }

    private fun startAdvertising(packetBytes: ByteArray?) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        try {
            advertiseCallback?.let { bluetoothLeAdvertiser?.stopAdvertising(it) }
        } catch (_: Exception) {}

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setTimeout(0)
            .build()

        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))

        if (packetBytes != null) {
            dataBuilder.addManufacturerData(MANUFACTURER_ID, packetBytes)
        }

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "MeshSetu BLE Advertising started.")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "MeshSetu BLE Advertising failed: $errorCode")
            }
        }

        try {
            bluetoothLeAdvertiser?.startAdvertising(settings, dataBuilder.build(), advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception advertising", e)
        }
    }

    private fun startScanning() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = device.address
                val name = try {
                    if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) device.name else null
                } catch (_: SecurityException) {
                    null
                }

                // Software-level filtering: Accept devices with our Manufacturer ID, Service UUID, or MeshSetu name
                val hasOurManufacturerData = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) != null
                val hasOurServiceUuid = result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true
                val hasMeshName = name != null && name.contains("Mesh", ignoreCase = true)

                // If it's another MeshSetu device (or broadcasting packets), register it
                if (hasOurManufacturerData || hasOurServiceUuid || hasMeshName) {
                    listener?.onPeerDiscovered(address, name ?: "MeshSetu Node")

                    if (hasOurManufacturerData) {
                        try {
                            val mfgData = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
                            if (mfgData != null) {
                                val json = String(mfgData, StandardCharsets.UTF_8)
                                val packet = gson.fromJson(json, MeshPacket::class.java)
                                if (packet != null && !seenBroadcastPackets.contains(packet.packetId)) {
                                    seenBroadcastPackets.add(packet.packetId)
                                    Log.d(TAG, "Received MeshSetu packet from $address: ${packet.packetId}")
                                    listener?.onPacketReceived(packet, address)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse MeshSetu packet", e)
                        }
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "MeshSetu BLE Scan failed: $errorCode")
            }
        }

        try {
            // Unfiltered hardware scan combined with software filtering prevents chipset scan filter rejections
            bluetoothLeScanner?.startScan(null, settings, scanCallback)
            Log.d(TAG, "MeshSetu Unfiltered Hardware Scan started.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception scanning", e)
        }
    }

    override fun sendPacket(packet: MeshPacket, destinationId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(packet)
                val bytes = json.toByteArray(StandardCharsets.UTF_8)
                startAdvertising(bytes)
                Log.d(TAG, "Broadcasted MeshSetu packet ${packet.packetId} via BLE advertising")
                
                delay(3000)
                startAdvertising(null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send packet via BLE broadcast", e)
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
