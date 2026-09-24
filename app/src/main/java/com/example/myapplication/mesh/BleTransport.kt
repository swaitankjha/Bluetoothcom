package com.example.myapplication.mesh

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
    private var gattServer: BluetoothGattServer? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var isDiscovering = false

    private val connectedGattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val serverDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7b420001-3456-7890-abcd-ef0123456789")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("7b420002-3456-7890-abcd-ef0123456789")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    override fun setListener(listener: TransportListener) {
        this.listener = listener
    }

    override fun startDiscovery() {
        if (isDiscovering) return
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) || !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            Log.e(TAG, "Missing BLE permissions for discovery/advertising")
            return
        }

        isDiscovering = true
        startGattServer()
        startAdvertising()
        startScanning()
    }

    override fun stopDiscovery() {
        isDiscovering = false
        try {
            if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                scanCallback?.let { callback ->
                    try {
                        bluetoothLeScanner?.stopScan(callback)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception stopping BLE scan", e)
                    }
                }
            }
            if (hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                advertiseCallback?.let { callback ->
                    try {
                        bluetoothLeAdvertiser?.stopAdvertising(callback)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception stopping BLE advertising", e)
                    }
                }
            }
            gattServer?.close()
            gattServer = null
            connectedGattClients.values.forEach { it.close() }
            connectedGattClients.clear()
            serverDevices.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE transport", e)
        }
    }

    private fun startGattServer() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        try {
            gattServer = bluetoothManager.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    val address = device.address
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "GATT Server: Connected to $address")
                        serverDevices.add(device)
                        listener?.onPeerConnected(address)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "GATT Server: Disconnected from $address")
                        serverDevices.remove(device)
                        listener?.onPeerDisconnected(address)
                    }
                }

                override fun onDescriptorWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    descriptor: BluetoothGattDescriptor,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (descriptor.uuid == CCCD_UUID) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                    } else {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    }
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (characteristic.uuid == CHARACTERISTIC_UUID && value != null) {
                        try {
                            val json = String(value, StandardCharsets.UTF_8)
                            val packet = gson.fromJson(json, MeshPacket::class.java)
                            Log.d(TAG, "Received packet via BLE GATT from ${device.address}: ${packet.packetId}")
                            listener?.onPacketReceived(packet, device.address)
                            if (responseNeeded) {
                                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse packet from BLE write", e)
                            if (responseNeeded) {
                                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                            }
                        }
                    }
                }
            })

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            )
            val descriptor = BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ)
            characteristic.addDescriptor(descriptor)
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)
            Log.d(TAG, "BLE GATT Server started successfully.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting GATT server", e)
        }
    }

    private fun startAdvertising() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "BLE Advertising started successfully.")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "BLE Advertising failed with error: $errorCode")
            }
        }

        try {
            bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting BLE advertising", e)
        }
    }

    private fun startScanning() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = device.address
                val name = try {
                    if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) device.name else null
                } catch (e: SecurityException) {
                    null
                }
                
                listener?.onPeerDiscovered(address, name)
                connectToGatt(device)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { result ->
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE Scan failed with error: $errorCode")
            }
        }

        try {
            bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
            Log.d(TAG, "BLE Scan started.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting BLE scan", e)
        }
    }

    private fun connectToGatt(device: BluetoothDevice) {
        val address = device.address
        if (connectedGattClients.containsKey(address)) return
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return

        try {
            Log.d(TAG, "Connecting to GATT server of $address...")
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "Connected to GATT server: $address")
                        connectedGattClients[address] = gatt
                        listener?.onPeerConnected(address)
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.d(TAG, "Disconnected from GATT server: $address")
                        connectedGattClients.remove(address)
                        gatt.close()
                        listener?.onPeerDisconnected(address)
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "Services discovered for $address")
                        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                        characteristic?.let { char ->
                            gatt.setCharacteristicNotification(char, true)
                            val descriptor = char.getDescriptor(CCCD_UUID)
                            if (descriptor != null) {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception connecting to GATT", e)
        }
    }

    override fun sendPacket(packet: MeshPacket, destinationId: String) {
        val json = gson.toJson(packet)
        val bytes = json.toByteArray(StandardCharsets.UTF_8)

        // 1. Send via GATT client connection if destination is specific or broadcast
        connectedGattClients.forEach { (address, gatt) ->
            if (destinationId == "broadcast" || destinationId == address) {
                scope.launch(Dispatchers.IO) {
                    try {
                        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return@launch
                        val service = gatt.getService(SERVICE_UUID)
                        val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                            } else {
                                @Suppress("DEPRECATION")
                                characteristic.value = bytes
                                @Suppress("DEPRECATION")
                                gatt.writeCharacteristic(characteristic)
                            }
                            Log.d(TAG, "Sent packet via BLE client to $address")
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception writing to BLE characteristic", e)
                    }
                }
            }
        }

        // 2. Also notify connected server devices if broadcasting
        if (destinationId == "broadcast" && gattServer != null) {
            val service = gattServer?.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic != null) {
                characteristic.value = bytes
                serverDevices.forEach { device ->
                    try {
                        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                            gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Security exception notifying device", e)
                    }
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
