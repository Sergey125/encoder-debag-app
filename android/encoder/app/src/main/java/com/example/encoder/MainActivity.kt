package com.example.encoder

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.encoder.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        private const val WHEEL_MAX = 600
        private const val ACTIVE_TIMEOUT_MS = 150L
        private const val UI_FRAME_MS = 16L        // ~60 кадров/с для стрелки
        private const val LOG_FLUSH_MS = 300L      // лог обновляем 3 раза в секунду
        private const val LOG_MAX_LINES = 200      // держим только последние строки
    }

    private lateinit var b: ActivityMainBinding
    private var ble: BleEncoderClient? = null
    private var connected = false

    private val dateTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logLines = ArrayDeque<String>()
    private var logDirty = false

    private lateinit var deviceAdapter: DeviceAdapter

    private val ui = Handler(Looper.getMainLooper())

    // Состояние, которое копится между кадрами отрисовки
    private var wheelPosition = 0
    private var pendingSteps = 0          // накопленные шаги со знаком
    private var lastForward = true
    private var wheelDirty = false

    private val hideActiveRunnable = Runnable { b.wheel.isActive = false }

    /** Перерисовывает колесо не чаще одного раза в кадр. */
    private val frameRunnable = object : Runnable {
        override fun run() {
            if (wheelDirty) {
                wheelDirty = false
                val steps = pendingSteps
                pendingSteps = 0
                if (steps != 0) {
                    b.wheel.rotateBy(abs(steps), steps > 0)
                    b.wheel.position = wheelPosition
                    b.wheel.isForward = lastForward
                    b.wheel.isActive = true
                    b.wheel.removeCallbacks(hideActiveRunnable)
                    b.wheel.postDelayed(hideActiveRunnable, ACTIVE_TIMEOUT_MS)
                }
            }
            ui.postDelayed(this, UI_FRAME_MS)
        }
    }

    /** Обновляет текстовый лог редко — он самый дорогой элемент. */
    private val logRunnable = object : Runnable {
        override fun run() {
            if (logDirty) {
                logDirty = false
                b.tvRaw.text = logLines.joinToString("\n")
            }
            ui.postDelayed(this, LOG_FLUSH_MS)
        }
    }

    private val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startScan()
        else b.tvStatus.text = "Нет разрешений Bluetooth"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        val controller = WindowInsetsControllerCompat(window, b.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(16 + bars.left, 16 + bars.top, 16 + bars.right, 16 + bars.bottom)
            insets
        }

        b.wheel.maxValue = WHEEL_MAX

        deviceAdapter = DeviceAdapter { found ->
            appendLog("Выбрано: ${found.name} (${found.address})")
            ble?.connectTo(found.device)
        }
        b.rvDevices.layoutManager = LinearLayoutManager(this)
        b.rvDevices.adapter = deviceAdapter

        ensureClient()

        b.btnScan.setOnClickListener { permLauncher.launch(permissions) }
        b.btnStopScan.setOnClickListener {
            ble?.stopScan()
            appendLog("Поиск остановлен")
        }

        ui.post(frameRunnable)
        ui.post(logRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, b.root)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun ensureClient() {
        if (ble != null) return
        ble = BleEncoderClient(
            context = this,
            deviceId = 1,
            onEncoder = { steps, forward -> onEncoderSteps(steps, forward) },
            onButton = { event -> appendLog("BTN $event") },
            onState = { state ->
                connected = state is BleState.Ready
                b.tvStatus.text = when (state) {
                    is BleState.Idle -> "Отключено"
                    is BleState.Scanning -> "Поиск устройств…"
                    is BleState.Connecting -> "Подключение…"
                    is BleState.Ready -> "Подключено"
                    is BleState.Error -> state.message
                }

                val scanning = state is BleState.Scanning
                b.btnScan.visibility = if (scanning) View.GONE else View.VISIBLE
                b.btnStopScan.visibility = if (scanning) View.VISIBLE else View.GONE

                if (connected) {
                    b.wheel.visibility = View.VISIBLE
                    b.rvDevices.visibility = View.GONE
                    wheelPosition = 0
                    pendingSteps = 0
                    b.wheel.position = 0
                    b.wheel.resetAngle()
                } else {
                    b.wheel.visibility = View.GONE
                    b.rvDevices.visibility = View.VISIBLE
                }

                appendLog("STATE: ${state::class.simpleName}")
            },
            onDeviceFound = { found -> deviceAdapter.addOrUpdate(found) }
        )
    }

    /**
     * Вызывается на каждый пакет от устройства (до 100 раз в секунду).
     * Здесь НИЧЕГО не рисуем — только копим состояние, отрисовка идёт
     * отдельно по таймеру, иначе UI не успевает за потоком данных.
     */
    private fun onEncoderSteps(steps: Int, forwardFromDir: Boolean?) {
        val magnitude = abs(steps)
        if (magnitude == 0) return

        val forward = forwardFromDir ?: true
        val delta = if (forward) magnitude else -magnitude

        wheelPosition = ((wheelPosition + delta) % WHEEL_MAX + WHEEL_MAX) % WHEEL_MAX
        pendingSteps += delta
        lastForward = forward
        wheelDirty = true

        appendLog("ENC |$magnitude| ${if (forward) "+" else "-"} pos=$wheelPosition")
    }

    private fun startScan() {
        ensureClient()
        deviceAdapter.clear()
        ble?.startScan()
    }

    /** Только копит строки, TextView трогает отдельный таймер. */
    private fun appendLog(line: String) {
        logLines.addFirst("${dateTimeFmt.format(Date())}  $line")
        while (logLines.size > LOG_MAX_LINES) logLines.removeLast()
        logDirty = true
    }

    override fun onDestroy() {
        ui.removeCallbacks(frameRunnable)
        ui.removeCallbacks(logRunnable)
        ble?.stop()
        super.onDestroy()
    }
}