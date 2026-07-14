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

        var onStateChanged: (() -> Unit)? = null
        var onProgress: ((Int, Int) -> Unit)? = null

        private const val SWIPE_DURATION_MS = 300L
        private const val PAUSE_BETWEEN = 200L
    }

    private var _isRecording = false
    val isRecording: Boolean get() = _isRecording

    private var _isPlaying = false
    val isPlaying: Boolean get() = _isPlaying

    private val _recordedSteps = mutableListOf<MacroStep>()
    val recordedSteps: List<MacroStep> get() = _recordedSteps.toList()

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Solo usado para reproducción — la grabación la hace el overlay
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

    // ─── CONTROL DE GRABACIÓN (solo estado, el overlay captura) ───

    fun startRecording() {
        _recordedSteps.clear()
        _isRecording = true
        _isPlaying = false
        Log.d(TAG, "Inicio grabación")
        onStateChanged?.invoke()
    }

    fun stopRecording() {
        _isRecording = false
        Log.d(TAG, "Grabación detenida: ${_recordedSteps.size} pasos")
        onStateChanged?.invoke()
    }

    fun addStep(step: MacroStep) {
        _recordedSteps.add(step)
        Log.d(TAG, "Step añadido: $step  (total: ${_recordedSteps.size})")
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

        val next = {
            playSteps(steps, index + 1, onDone)
        }

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.post(next)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesto cancelado: $step")
                handler.post(next)
            }
        }

        when (step) {
            is MacroStep.Touch -> {
                val path = Path().apply { moveTo(step.x, step.y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, step.duration))
                    .build()
                dispatchGesture(gesture, callback, null)
            }

            is MacroStep.Swipe -> {
                val path = Path().apply {
                    moveTo(step.x1, step.y1)
                    lineTo(step.x2, step.y2)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
                    .build()
                dispatchGesture(gesture, callback, null)
            }

            is MacroStep.Pause -> {
                handler.postDelayed({
                    playSteps(steps, index + 1, onDone)
                }, step.ms)
            }
        }
    }
}
