package com.pebbllink.app

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
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    /** The watch used last time, so it can be reconnected without scanning. */
    val savedAddress: String? = null,
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
    private val prefs = appContext.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var wantConnected = false
    private var retries = 0
    private var connectedAt = 0L

    private val _ui = MutableStateFlow(LinkUi(savedAddress = prefs.getString("address", null)))
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
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "a scan is already running"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android could not register the scan; turn Bluetooth off and on and try again"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "this phone does not support BLE scanning"
                SCAN_FAILED_INTERNAL_ERROR -> "internal Bluetooth error; turn Bluetooth off and on"
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "the Bluetooth chip is out of resources; close other Bluetooth apps"
                6 -> "too many scans started in a short time; wait 30 seconds"
                else -> "unknown error"
            }
            log("Scan failed ($errorCode): $reason")
            _ui.update { it.copy(state = LinkState.Idle) }
        }
    }

    fun startScan() {
        if (adapter == null) return log("This phone has no Bluetooth")
        if (!adapter.isEnabled) return log("Bluetooth is off. Turn it on, then scan again")
        val locationManager = appContext.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT < 31 && locationManager?.isLocationEnabled != true) {
            return log("Location is off. Android 11 needs it on to scan for Bluetooth devices")
        }
        val scanner = adapter.bluetoothLeScanner ?: return log("Bluetooth scanner not available; turn Bluetooth off and on")
        _ui.update { it.copy(state = LinkState.Scanning, devices = emptyList()) }
        scanner.startScan(scanCallback)
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _ui.update { if (it.state == LinkState.Scanning) it.copy(state = LinkState.Idle) else it }
    }

    fun connect(address: String) {
        stopScan()
        wantConnected = true
        retries = 0
        prefs.edit().putString("address", address).apply()
        _ui.update { it.copy(savedAddress = address) }
        open(address)
    }

    fun connectSaved() {
        _ui.value.savedAddress?.let { connect(it) }
    }

    private fun open(address: String) {
        val device = adapter?.getRemoteDevice(address) ?: return
        _ui.update { it.copy(state = LinkState.Connecting, characteristics = emptyList()) }
        log("Connecting to $address")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        wantConnected = false
        clearQueue()
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
    }

    /** Forgets the saved watch, for when you want to pair a different one. */
    fun forget() {
        disconnect()
        prefs.edit().remove("address").apply()
        _ui.update { it.copy(savedAddress = null) }
    }

    /** Asks Android to bond with the watch. Some watches ignore commands until they are bonded. */
    fun pair() {
        val device = gatt?.device ?: return
        log(if (device.createBond()) "Pairing requested" else "Pairing request failed")
    }

    // Android allows only one GATT operation at a time, so reads, writes and subscriptions wait their turn here.
    private val queue = ArrayDeque<() -> Unit>()
    private var busy = false
    private val unstick = Runnable { finishOp() }

    private fun enqueue(op: () -> Unit) {
        queue.addLast(op)
        if (!busy) nextOp()
    }

    private fun nextOp() {
        val op = queue.removeFirstOrNull()
        if (op == null) {
            busy = false
            return
        }
        busy = true
        // Some watches never answer a write; do not wait forever.
        handler.postDelayed(unstick, OP_TIMEOUT_MS)
        op()
    }

    private fun finishOp() {
        handler.removeCallbacks(unstick)
        nextOp()
    }

    private fun clearQueue() {
        queue.clear()
        busy = false
        handler.removeCallbacks(unstick)
    }

    private fun find(info: CharInfo): BluetoothGattCharacteristic? = gatt?.getService(info.service)?.getCharacteristic(info.uuid)

    /** Turns on notifications for a characteristic so replies from the watch show in the log. */
    fun subscribe(info: CharInfo) = enqueue {
        val g = gatt
        val ch = find(info)
        val cccd = ch?.getDescriptor(CCCD)
        if (g == null || ch == null || cccd == null) {
            log("Cannot subscribe to ${info.uuid.short()}")
            finishOp()
        } else {
            g.setCharacteristicNotification(ch, true)
            val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            log("Subscribing to ${info.uuid.short()}")
        }
    }

    fun write(info: CharInfo, bytes: ByteArray) = enqueue {
        val g = gatt
        val ch = find(info)
        if (g == null || ch == null) {
            finishOp()
        } else {
            val noResponse = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
            val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, bytes, type)
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = type
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            log("TX ${info.uuid.short()}  ${bytes.toHex()}")
        }
    }

    fun read(info: CharInfo) = enqueue {
        val g = gatt
        val ch = find(info)
        if (g == null || ch == null || !g.readCharacteristic(ch)) finishOp()
    }

    /** Right after connecting: read the standard info and turn on every notification, as the vendor app would. */
    private fun setUp(chars: List<CharInfo>) {
        chars.filter { it.canRead && it.service.short() in STANDARD_SERVICES }.forEach { read(it) }
        chars.filter { it.canNotify }.forEach { subscribe(it) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedAt = SystemClock.elapsedRealtime()
                log("Connected, finding services")
                g.discoverServices()
            } else {
                val lived = (SystemClock.elapsedRealtime() - connectedAt) / 1000.0
                log("Disconnected (status $status) after ${"%.1f".format(lived)}s" + if (status == 19) " - the watch hung up" else "")
                clearQueue()
                g.close()
                if (gatt === g) gatt = null
                _ui.update { it.copy(state = LinkState.Idle, characteristics = emptyList()) }
                if (lived > STABLE_SECONDS) retries = 0
                retryLater(g.device.address)
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
            setUp(chars)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("Subscribe failed on ${d.characteristic.uuid.short()} (status $status)")
            finishOp()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) log("Write failed on ${c.uuid.short()} (status $status)")
            finishOp()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            log("RX ${c.uuid.short()}  ${value.toHex()}")
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            log("READ ${c.uuid.short()}  ${value.toHex()}${value.asText()}")
            finishOp()
        }

        // Android 11 and 12 call these older versions instead of the ones above.
        @Deprecated("Used below Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) log("RX ${c.uuid.short()}  ${c.value?.toHex().orEmpty()}")
        }

        @Deprecated("Used below Android 13")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                val value = c.value ?: ByteArray(0)
                log("READ ${c.uuid.short()}  ${value.toHex()}${value.asText()}")
                finishOp()
            }
        }
    }

    /** Tries again a few times when the watch drops out, for example when it goes out of range. */
    private fun retryLater(address: String) {
        if (!wantConnected || retries >= MAX_RETRIES) return
        retries++
        log("Retrying in ${RETRY_DELAY_MS / 1000}s (attempt $retries of $MAX_RETRIES)")
        handler.postDelayed({ if (wantConnected && gatt == null) open(address) }, RETRY_DELAY_MS)
    }

    /** Lets the screen add a line to the log, for example when a permission is refused. */
    fun note(line: String) = log(line)

    private fun log(line: String) {
        _ui.update { it.copy(log = (it.log + line).takeLast(300)) }
    }

    companion object {
        private const val OP_TIMEOUT_MS = 1500L
        private const val STABLE_SECONDS = 10
        private val STANDARD_SERVICES = setOf("180a", "180f")
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 3000L
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

fun UUID.short(): String = toString().substring(4, 8).let { if (toString().endsWith("-0000-1000-8000-00805f9b34fb")) it else toString() }

/** Shows the bytes as text too, when they are all printable, so version strings are readable. */
fun ByteArray.asText(): String =
    if (isNotEmpty() && all { it in 32..126 }) "  \"${String(this)}\"" else ""

fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

/** Parses "0a ff 10" or "0aff10" into bytes, or null if it is not valid hex. */
fun parseHex(text: String): ByteArray? {
    val clean = text.filterNot { it.isWhitespace() }
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    return runCatching { ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
}
