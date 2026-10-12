package com.example.filtroakashi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Pestaña de redimensionado: círculo semitransparente con aro y punto amarillos.
 * Se pone amarilla mientras se la toca.
 */
class TabView(context: Context) : View(context) {

    var tocada = false

    private val d = resources.displayMetrics.density
    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(179, 33, 33, 33)
    }
    private val fondoTocada = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HandleView.AMARILLO
    }
    private val aro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HandleView.AMARILLO
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val punto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HandleView.AMARILLO
    }
    private val puntoTocada = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radio = minOf(width, height) / 2f - 2f * d
        canvas.drawCircle(cx, cy, radio, if (tocada) fondoTocada else fondo)
        canvas.drawCircle(cx, cy, radio, aro)
        canvas.drawCircle(cx, cy, 4f * d, if (tocada) puntoTocada else punto)
    }
}
