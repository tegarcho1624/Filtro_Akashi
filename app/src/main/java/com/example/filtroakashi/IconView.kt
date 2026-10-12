package com.example.filtroakashi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Ícono de "sol/ajustes": abre el panel de opacidad. Mismo estilo que las pestañas. */
class IconView(context: Context) : View(context) {

    var tocada = false

    private val d = resources.displayMetrics.density
    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(179, 33, 33, 33) }
    private val fondoTocada = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HandleView.AMARILLO }
    private val aro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HandleView.AMARILLO
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HandleView.AMARILLO
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val trazoTocada = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val centro = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HandleView.AMARILLO }
    private val centroTocada = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radio = minOf(width, height) / 2f - 2f * d
        canvas.drawCircle(cx, cy, radio, if (tocada) fondoTocada else fondo)
        canvas.drawCircle(cx, cy, radio, aro)

        // Sol: círculo central + 8 rayos.
        val pTrazo = if (tocada) trazoTocada else trazo
        canvas.drawCircle(cx, cy, 4f * d, if (tocada) centroTocada else centro)
        val r1 = 7f * d
        val r2 = 10f * d
        for (i in 0 until 8) {
            val ang = Math.toRadians(i * 45.0)
            val c = Math.cos(ang).toFloat()
            val s = Math.sin(ang).toFloat()
            canvas.drawLine(cx + c * r1, cy + s * r1, cx + c * r2, cy + s * r2, pTrazo)
        }
    }
}
