package com.wristlink.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** A device seen while scanning. */
data class FoundDevice(val address: String, val name: String?, val rssi: Int)

/** One GATT characteristic on the watch, with what it can do. */
data class CharInfo(
    val service: UUID,
    val uuid: UUID,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canNotify: Boolean,
)

enum class LinkState { Idle, Scanning, Connecting, Connected }

data class LinkUi(
    val state: LinkState = LinkState.Idle,
    val devices: List<FoundDevice> = emptyList(),
    val characteristics: List<CharInfo> = emptyList(),
    val log: List<String> = emptyList(),
)

/**
 * Talks to a Bluetooth LE watch. The permissions (BLUETOOTH_SCAN and BLUETOOTH_CONNECT, or location on Android 11)
 * must be granted before any call here; the UI asks for them first.
 */
@SuppressLint("MissingPermission")
class WatchLink(context: Context) {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null

    private val _ui = MutableStateFlow(LinkUi())
    val ui: StateFlow<LinkUi> = _ui

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val found = FoundDevice(result.device.address, result.scanRecord?.deviceName ?: result.device.name, result.rssi)
            _ui.update { s ->
                val rest = s.devices.filterNot { it.address == found.address }
                s.copy(devices = (rest + found).sortedByDescending { it.rssi })
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed ($errorCode)")
            _ui.update { it.copy(state = LinkState.Idle) }
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return log("Bluetooth is off")
        _ui.update { it.copy(state = LinkState.Scanning, devices = emptyList()) }
        scanner.startScan(scanCallback)
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _ui.update { if (it.state == LinkState.Scanning) it.copy(state = LinkState.Idle) else it }
    }

    fun connect(address: String) {
        stopScan()
        val device = adapter?.getRemoteDevice(address) ?: return
        _ui.update { it.copy(state = LinkState.Connecting, characteristics = emptyList()) }
        log("Connecting to $address")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    /** Turns on notifications for a characteristic so replies from the watch show in the log. */
    fun subscribe(info: CharInfo) {
        val g = gatt ?: return
        val ch = g.getService(info.service)?.getCharacteristic(info.uuid) ?: return
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD) ?: return log("No notify descriptor on ${info.uuid.short()}")
        val value = if (info.canNotify && ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        g.writeDescriptor(cccd, value)
        log("Subscribing to ${info.uuid.short()}")
    }

    fun write(info: CharInfo, bytes: ByteArray) {
        val g = gatt ?: return
        val ch = g.getService(info.service)?.getCharacteristic(info.uuid) ?: return
        val noResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        g.writeCharacteristic(ch, bytes, type)
        log("TX ${info.uuid.short()}  ${bytes.toHex()}")
    }

    fun read(info: CharInfo) {
        val g = gatt ?: return
        g.getService(info.service)?.getCharacteristic(info.uuid)?.let { g.readCharacteristic(it) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected, finding services")
                g.discoverServices()
            } else {
                log("Disconnected (status $status)")
                g.close()
                if (gatt === g) gatt = null
                _ui.update { it.copy(state = LinkState.Idle, characteristics = emptyList()) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val chars = g.services.flatMap { service ->
                service.characteristics.map { c ->
                    CharInfo(
                        service = service.uuid,
                        uuid = c.uuid,
                        canRead = c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                        canWrite = c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0,
                        canNotify = c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
                    )
                }
            }
            log("Found ${chars.size} characteristics")
            _ui.update { it.copy(state = LinkState.Connected, characteristics = chars) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            log("RX ${c.uuid.short()}  ${value.toHex()}")
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            log("READ ${c.uuid.short()}  ${value.toHex()}")
        }
    }

    private fun log(line: String) {
        _ui.update { it.copy(log = (it.log + line).takeLast(300)) }
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

fun UUID.short(): String = toString().substring(4, 8).let { if (toString().endsWith("-0000-1000-8000-00805f9b34fb")) it else toString() }

fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

/** Parses "0a ff 10" or "0aff10" into bytes, or null if it is not valid hex. */
fun parseHex(text: String): ByteArray? {
    val clean = text.filterNot { it.isWhitespace() }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    return runCatching { ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
}
