package com.example.encoder

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

private val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
private val TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

data class FoundDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int
)

@SuppressLint("MissingPermission")
class BleEncoderClient(
    private val context: Context,
    private val deviceId: Int = 1,
    private val onEncoder: (steps: Int, forward: Boolean?) -> Unit,
    private val onButton: (event: String) -> Unit,
    private val onState: (BleState) -> Unit,
    private val onDeviceFound: (FoundDevice) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val buffer = StringBuilder()
    private val seenAddresses = mutableSetOf<String>()

    fun isBluetoothEnabled() = adapter?.isEnabled == true

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            if (!seenAddresses.add(address)) return
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "(без имени)"
            main.post {
                onDeviceFound(FoundDevice(result.device, name, address, result.rssi))
            }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            post(BleState.Error("Сканирование не удалось: код $errorCode"))
        }
    }

    fun startScan() {
        if (!isBluetoothEnabled()) {
            post(BleState.Error("Bluetooth выключен"))
            return
        }
        scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) return

        seenAddresses.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        post(BleState.Scanning)
        scanner?.startScan(null, settings, scanCallback)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(scanCallback) }
        post(BleState.Idle)
    }

    fun connectTo(device: BluetoothDevice) {
        stopScan()
        post(BleState.Connecting)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun stop() {
        stopScan()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        post(BleState.Idle)
    }

    private fun post(s: BleState) = main.post { onState(s) }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    main.postDelayed({ g.discoverServices() }, 300)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runCatching { g.close() }
                    gatt = null
                    buffer.setLength(0)
                    post(BleState.Idle)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                post(BleState.Error("Сервисы не найдены ($status)"))
                return
            }
            val ch = g.getService(SERVICE_UUID)?.getCharacteristic(TX_CHAR_UUID)
            if (ch == null) {
                post(BleState.Error("Характеристика TX отсутствует"))
                return
            }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            post(BleState.Ready)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleBytes(ch.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleBytes(value)
        }
    }

    private fun handleBytes(bytes: ByteArray) {
        buffer.append(String(bytes, Charsets.UTF_8))
        while (true) {
            val idx = buffer.indexOf("\n")
            if (idx < 0) break
            val line = buffer.substring(0, idx).trim()
            buffer.delete(0, idx + 1)
            if (line.isNotEmpty()) parseLine(line)
        }
        if (buffer.length > 256) buffer.setLength(0)
    }

    private fun parseLine(line: String) {
        val parts = line.split("|")
        val encPart = parts.firstOrNull { it.startsWith("ENC:") }
        val dirPart = parts.firstOrNull { it.startsWith("DIR:") }
        val btnPart = parts.firstOrNull { it.startsWith("BTN:") }

        if (encPart != null) {
            val steps = encPart.removePrefix("ENC:").toIntOrNull() ?: return
            // DIR приходит напрямую от прошивки — надёжнее, чем угадывать
            // направление по знаку дельты на границе 0/599.
            val forward: Boolean? = when (dirPart?.removePrefix("DIR:")) {
                "+" -> true
                "-" -> false
                else -> null
            }
            main.post { onEncoder(steps, forward) }
        } else if (btnPart != null) {
            val event = btnPart.removePrefix("BTN:")
            main.post { onButton(event) }
        }
    }
}