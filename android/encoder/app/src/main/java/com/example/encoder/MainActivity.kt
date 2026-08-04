package com.example.encoder

import android.Manifest
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.encoder.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val WHEEL_MAX = 600
        private const val ACTIVE_TIMEOUT_MS = 150L
        private const val LOG_FLUSH_MS = 300L
        private const val LOG_MAX_LINES = 200
    }

    private lateinit var b: ActivityMainBinding
    private var ble: BleEncoderClient? = null
    private var connected = false

    private val dateTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logLines = ArrayDeque<String>()
    private var logDirty = false

    private lateinit var deviceAdapter: DeviceAdapter

    private val ui = Handler(Looper.getMainLooper())

    private var wheelPosition = 0
    private val hideActiveRunnable = Runnable { b.wheel.isActive = false }

    private var scanDialog: Dialog? = null
    private var dialogStatus: android.widget.TextView? = null
    private var dialogScanBtn: MaterialButton? = null
    private var dialogStopBtn: MaterialButton? = null

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
        if (result.values.all { it }) showScanDialog()
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
            scanDialog?.dismiss()
        }

        ensureClient()

        b.btnConnect.setOnClickListener {
            if (connected) {
                ble?.stop()
                appendLog("Отключено вручную")
            } else {
                permLauncher.launch(permissions)
            }
        }

        ui.post(logRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, b.root)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showScanDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_scan)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#121212")))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val rv = dialog.findViewById<RecyclerView>(R.id.rvDialogDevices)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = deviceAdapter

        dialogStatus = dialog.findViewById(R.id.tvDialogStatus)
        dialogScanBtn = dialog.findViewById(R.id.btnDialogScan)
        dialogStopBtn = dialog.findViewById(R.id.btnDialogStop)

        dialogScanBtn?.setOnClickListener {
            deviceAdapter.clear()
            ble?.startScan()
        }

        dialogStopBtn?.setOnClickListener {
            ble?.stopScan()
            appendLog("Поиск остановлен")
        }

        dialog.findViewById<MaterialButton>(R.id.btnClose).setOnClickListener {
            ble?.stopScan()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            ble?.stopScan()
            scanDialog = null
            dialogStatus = null
            dialogScanBtn = null
            dialogStopBtn = null
        }

        scanDialog = dialog
        dialog.show()

        deviceAdapter.clear()
        ble?.startScan()
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

                val statusText = when (state) {
                    is BleState.Idle -> "Отключено"
                    is BleState.Scanning -> "Поиск устройств…"
                    is BleState.Connecting -> "Подключение…"
                    is BleState.Ready -> "Подключено"
                    is BleState.Error -> state.message
                }
                b.tvStatus.text = statusText
                dialogStatus?.text = statusText

                b.btnConnect.text =
                    if (connected) "Отключить" else "Подключить устройство"

                val scanning = state is BleState.Scanning
                dialogScanBtn?.isEnabled = !scanning
                dialogStopBtn?.isEnabled = scanning

                if (connected) {
                    b.wheel.visibility = View.VISIBLE
                    wheelPosition = 0
                    b.wheel.position = 0
                    b.wheel.resetAngle()
                    scanDialog?.dismiss()
                } else {
                    b.wheel.visibility = View.GONE
                }

                appendLog("STATE: ${state::class.simpleName}")
            },
            onDeviceFound = { found -> deviceAdapter.addOrUpdate(found) }
        )
    }

    /**
     * Число из ENC не используется вообще — только направление из DIR.
     * Каждый входящий пакет считается ровно за один шаг.
     */
    private fun onEncoderSteps(steps: Int, forwardFromDir: Boolean?) {
        // Без DIR определить направление нечем — пакет пропускаем.
        val forward = forwardFromDir ?: return

        val delta = if (forward) 1 else -1
        wheelPosition = ((wheelPosition + delta) % WHEEL_MAX + WHEEL_MAX) % WHEEL_MAX

        b.wheel.position = wheelPosition
        b.wheel.isForward = forward
        b.wheel.rotateBy(1, forward)

        b.wheel.isActive = true
        b.wheel.removeCallbacks(hideActiveRunnable)
        b.wheel.postDelayed(hideActiveRunnable, ACTIVE_TIMEOUT_MS)

        appendLog("STEP ${if (forward) "+" else "-"} pos=$wheelPosition")
    }

    private fun appendLog(line: String) {
        logLines.addFirst("${dateTimeFmt.format(Date())}  $line")
        while (logLines.size > LOG_MAX_LINES) logLines.removeLast()
        logDirty = true
    }

    override fun onDestroy() {
        ui.removeCallbacks(logRunnable)
        scanDialog?.dismiss()
        ble?.stop()
        super.onDestroy()
    }
}