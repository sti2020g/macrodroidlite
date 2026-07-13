package com.ruben.macrodroidlite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.ruben.macrodroidlite.databinding.OverlayWidgetBinding

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private val binding: OverlayWidgetBinding by lazy {
        OverlayWidgetBinding.bind(overlayView)
    }

    private var repeatCount = 1
    private var isRecording = false
    private var isPlaying = false
    private var stepCount = 0

    // Para arrastrar el overlay
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    companion object {
        private const val NOTIFICATION_CHANNEL = "macrodroid_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Inflar el layout del overlay
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_widget, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 200

        windowManager.addView(overlayView, params)

        // Configurar botones
        setupButtons()
        setupDrag(params)
        updateUI()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "MacroDroid Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Overlay flotante para macros"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("MacroDroid Lite")
            .setContentText("Overlay activo — graba y reproduce deslizamientos")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun setupButtons() {
        binding.btnRecord.setOnClickListener {
            val service = MacroAccessibilityService.instance
            if (service == null) {
                binding.txtStatus.text = "Activa el servicio de accesibilidad"
                return@setOnClickListener
            }

            if (!service.isRecording && !service.isPlaying) {
                service.startRecording()
                binding.txtStatus.text = "🔴 Grabando..."
                binding.btnRecord.visibility = View.GONE
                binding.btnStop.visibility = View.VISIBLE
                binding.btnPlay.isEnabled = false
            }
        }

        binding.btnStop.setOnClickListener {
            val service = MacroAccessibilityService.instance
            service?.stopRecording()
            stopPlayback()
            binding.txtStatus.text = "Grabación detenida"
            binding.btnRecord.visibility = View.VISIBLE
            binding.btnStop.visibility = View.GONE
            binding.btnPlay.isEnabled = true
            updateUI()
        }

        binding.btnPlay.setOnClickListener {
            val service = MacroAccessibilityService.instance
            if (service == null) {
                binding.txtStatus.text = "Activa el servicio de accesibilidad"
                return@setOnClickListener
            }

            if (service.recordedSteps.isEmpty()) {
                binding.txtStatus.text = "No hay nada grabado"
                return@setOnClickListener
            }

            service.playSequence(repeatCount)
            binding.txtStatus.text = "▶️ Reproduciendo ($repeatCount vez/veces)..."
            binding.btnPlay.isEnabled = false
            binding.btnRecord.isEnabled = false
        }

        binding.btnClose.setOnClickListener {
            stopSelf()
        }

        binding.btnPlus.setOnClickListener {
            repeatCount = (repeatCount + 1).coerceAtMost(999)
            binding.txtRepeatCount.text = repeatCount.toString()
        }

        binding.btnMinus.setOnClickListener {
            repeatCount = (repeatCount - 1).coerceAtLeast(1)
            binding.txtRepeatCount.text = repeatCount.toString()
        }
    }

    private fun stopPlayback() {
        val service = MacroAccessibilityService.instance
        service?.stopPlayback()
        binding.btnRecord.visibility = View.VISIBLE
        binding.btnStop.visibility = View.GONE
        binding.btnPlay.isEnabled = true
        binding.btnRecord.isEnabled = true
        binding.txtStatus.text = "Listo"
    }

    private fun setupDrag(params: WindowManager.LayoutParams) {
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun updateUI() {
        val service = MacroAccessibilityService.instance
        val steps = service?.recordedSteps?.size ?: 0
        binding.txtStatus.text = if (steps > 0) {
            "$steps gestos grabados"
        } else {
            "Listo"
        }

        MacroAccessibilityService.onStateChanged = {
            val s = MacroAccessibilityService.instance
            if (s?.isPlaying == false && isPlaying) {
                // Ha terminado la reproducción
                binding.btnRecord.visibility = View.VISIBLE
                binding.btnStop.visibility = View.GONE
                binding.btnPlay.isEnabled = true
                binding.btnRecord.isEnabled = true
                binding.txtStatus.text = "✅ Reproducción completada"
            }
            isPlaying = s?.isPlaying ?: false
            isRecording = s?.isRecording ?: false
            stepCount = s?.recordedSteps?.size ?: 0
            if (!isRecording && !isPlaying) {
                binding.txtStatus.text = if (stepCount > 0) "$stepCount gestos grabados" else "Listo"
            }
        }
    }
}
