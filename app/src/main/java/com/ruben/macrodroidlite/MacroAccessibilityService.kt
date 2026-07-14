package com.ruben.macrodroidlite

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MacroAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MacroAccess"
        var instance: MacroAccessibilityService? = null
            private set

        // Listener para cambios de estado
        var onStateChanged: (() -> Unit)? = null
        var onProgress: ((Int, Int) -> Unit)? = null

        private const val SWIPE_DURATION_MS = 300L
        private const val PAUSE_BETWEEN = 200L
    }

    // Estados (instancia, no companion)
    private var _isRecording = false
    val isRecording: Boolean get() = _isRecording

    private var _isPlaying = false
    val isPlaying: Boolean get() = _isPlaying

    // Secuencia grabada (instancia)
    private val _recordedSteps = mutableListOf<MacroStep>()
    val recordedSteps: List<MacroStep> get() = _recordedSteps.toList()

    // Coordenadas de inicio de toque (instancia)
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    private var isTrackingTouch = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!_isRecording) return
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                // Usar coordenadas directas del evento (event.x / event.y)
                // event.source puede ser null al tocar fondos/áreas vacías
                touchStartX = event.x
                touchStartY = event.y
                touchStartTime = System.currentTimeMillis()
                isTrackingTouch = true
                Log.d(TAG, "Touch start at ($touchStartX, $touchStartY) source=${event.source != null}")
            }

            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                if (!isTrackingTouch) return
                val endTime = System.currentTimeMillis()
                val elapsed = endTime - touchStartTime
                isTrackingTouch = false

                val endX = event.x
                val endY = event.y

                // Calcular distancia del gesto
                val dx = endX - touchStartX
                val dy = endY - touchStartY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)

                Log.d(TAG, "Touch end at ($endX, $endY) dist=$dist elapsed=$elapsed")

                if (dist < 30f) {
                    _recordedSteps.add(MacroStep.Touch(touchStartX, touchStartY, elapsed.coerceAtMost(100L)))
                    Log.d(TAG, "Recorded TAP at ($touchStartX, $touchStartY)")
                } else {
                    _recordedSteps.add(MacroStep.Swipe(touchStartX, touchStartY, endX, endY))
                    Log.d(TAG, "Recorded SWIPE ($touchStartX,$touchStartY) -> ($endX,$endY)")
                }
                onStateChanged?.invoke()
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrumpido")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isRecording = false
        _isPlaying = false
    }

    // ─── CONTROL DE GRABACIÓN ───

    fun startRecording() {
        _recordedSteps.clear()
        _isRecording = true
        _isPlaying = false
        isTrackingTouch = false
        Log.d(TAG, "Inicio grabación")
        onStateChanged?.invoke()
    }

    fun stopRecording() {
        _isRecording = false
        isTrackingTouch = false
        Log.d(TAG, "Grabación detenida: ${_recordedSteps.size} pasos")
        onStateChanged?.invoke()
    }

    // ─── REPRODUCCIÓN ───

    fun playSequence(times: Int) {
        if (_recordedSteps.isEmpty()) {
            Log.w(TAG, "No hay pasos grabados")
            return
        }
        if (_isPlaying) return

        _isPlaying = true
        onStateChanged?.invoke()

        val allSteps = mutableListOf<MacroStep>()
        repeat(times) {
            // Añadir pausa entre repeticiones (excepto la primera)
            if (it > 0) allSteps.add(MacroStep.Pause(PAUSE_BETWEEN))
            allSteps.addAll(_recordedSteps)
        }

        playSteps(allSteps, 0) {
            _isPlaying = false
            onStateChanged?.invoke()
        }
    }

    fun stopPlayback() {
        _isPlaying = false
        _recordedSteps.clear()
        onStateChanged?.invoke()
    }

    private fun playSteps(steps: List<MacroStep>, index: Int, onDone: () -> Unit) {
        if (!_isPlaying) {
            onDone()
            return
        }
        if (index >= steps.size) {
            onDone()
            return
        }

        val step = steps[index]
        onProgress?.invoke(index + 1, steps.size)
        Log.d(TAG, "Reproduciendo paso $index/${steps.size}: $step")

        when (step) {
            is MacroStep.Touch -> {
                val path = Path().apply { moveTo(step.x, step.y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, step.duration))
                    .build()
                dispatchGesture(gesture, null, null)
                handler.postDelayed({
                    playSteps(steps, index + 1, onDone)
                }, step.duration + 50L)
            }

            is MacroStep.Swipe -> {
                val path = Path().apply {
                    moveTo(step.x1, step.y1)
                    lineTo(step.x2, step.y2)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, step.duration))
                    .build()
                dispatchGesture(gesture, null, null)
                handler.postDelayed({
                    playSteps(steps, index + 1, onDone)
                }, step.duration + 50L)
            }

            is MacroStep.Pause -> {
                handler.postDelayed({
                    playSteps(steps, index + 1, onDone)
                }, step.ms)
            }
        }
    }
}
