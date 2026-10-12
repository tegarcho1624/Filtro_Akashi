package com.example.filtroakashi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Burbuja flotante: roja cuando el filtro está activo, gris cuando no. */
class BubbleView(context: Context) : View(context) {

    var activo = false
        set(valor) {
            field = valor
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val rojo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 0, 0) }
    private val gris = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(158, 158, 158) }
    private val aro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HandleView.AMARILLO
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radio = minOf(width, height) / 2f - 2f * d
        canvas.drawCircle(cx, cy, radio, if (activo) rojo else gris)
        canvas.drawCircle(cx, cy, radio, aro)
    }
}
