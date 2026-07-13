package com.ruben.macrodroidlite

import android.graphics.Path

/**
 * Un paso en la secuencia grabada: un toque, deslizamiento o pausa.
 */
sealed class MacroStep {
    data class Touch(val x: Float, val y: Float, val duration: Long = 50L) : MacroStep()
    data class Swipe(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val duration: Long = 300L
    ) : MacroStep()
    data class Pause(val ms: Long) : MacroStep()
}

/**
 * Secuencia completa de macros grabada.
 */
data class MacroSequence(
    val steps: List<MacroStep>,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val recordedAt: Long = System.currentTimeMillis()
)
