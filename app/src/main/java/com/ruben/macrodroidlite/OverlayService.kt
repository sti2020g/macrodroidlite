package com.ruben.macrodroidlite

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ruben.macrodroidlite.databinding.OverlayWidgetBinding

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private val binding: OverlayWidgetBinding by lazy {
        OverlayWidgetBinding.bind(overlayView)
    }

    // Captura overlay a pantalla completa
    private var captureOverlay: View? = null

    private var repeatCount = 1
    private var isRecording = false
    private var isPlaying = false
    private var stepCount = 0

    // Para arrastrar el overlay
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // Coordenadas de toque durante grabación
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    private var isTrackingTouch = false

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
        removeCaptureOverlay()
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
                binding.txtStatus.text = "Abrir ajustes de accesibilidad..."
                openAccessibilitySettings()
                return@setOnClickListener
            }

            service.startRecording()
            showCaptureOverlay(service)
            binding.txtStatus.text = "🔴 Grabando... Toca la pantalla"
            binding.btnRecord.visibility = View.GONE
            binding.btnStop.visibility = View.VISIBLE
            binding.btnPlay.isEnabled = false
        }

        binding.btnStop.setOnClickListener {
            val service = MacroAccessibilityService.instance
            service?.stopRecording()
            stopPlayback()
            removeCaptureOverlay()
            binding.txtStatus.text = if (service?.recordedSteps?.size ?: 0 > 0) {
                "${service?.recordedSteps?.size} gestos grabados"
            } else {
                "Grabación detenida (sin gestos)"
            }
            binding.btnRecord.visibility = View.VISIBLE
            binding.btnStop.visibility = View.GONE
            binding.btnPlay.isEnabled = service?.recordedSteps?.isNotEmpty() ?: false
            updateUI()
        }

        binding.btnPlay.setOnClickListener {
            val service = MacroAccessibilityService.instance
            if (service == null) {
                binding.txtStatus.text = "Abrir ajustes de accesibilidad..."
                openAccessibilitySettings()
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

    private fun showCaptureOverlay(service: MacroAccessibilityService) {
        removeCaptureOverlay()

        val view = View(this)
        view.setBackgroundColor(android.graphics.Color.argb(1, 0, 0, 0)) // casi transparente (1/255 alpha)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        view.setOnTouchListener { _, event ->
            if (!service.isRecording) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    touchStartTime = System.currentTimeMillis()
                    isTrackingTouch = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isTrackingTouch) return@setOnTouchListener false
                    val endX = event.x
                    val endY = event.y
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    isTrackingTouch = false

                    val dx = endX - touchStartX
                    val dy = endY - touchStartY
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)

                    if (dist < 30f) {
                        service.addStep(MacroStep.Touch(touchStartX, touchStartY, elapsed.coerceAtMost(100L)))
                    } else {
                        service.addStep(MacroStep.Swipe(touchStartX, touchStartY, endX, endY))
                    }

                    // Actualizar contador en el widget
                    binding.txtStatus.text = "🔴 ${service.recordedSteps.size} gestos"
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
        captureOverlay = view
    }

    private fun removeCaptureOverlay() {
        captureOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            captureOverlay = null
        }
    }

    private fun stopPlayback() {
        val service = MacroAccessibilityService.instance
        service?.stopPlayback()
        binding.btnRecord.visibility = View.VISIBLE
        binding.btnStop.visibility = View.GONE
        binding.btnPlay.isEnabled = (service?.recordedSteps?.size ?: 0) > 0
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

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "Abre Ajustes → Accesibilidad → MacroDroid Lite", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
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
