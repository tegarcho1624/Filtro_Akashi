package com.example.filtroakashi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Agarradera visual: barra delgada semitransparente con tres puntitos amarillos
 * y una línea fina amarilla abajo. Es la única parte del filtro que recibe toques.
 *
 * VERSIÓN DIAGNÓSTICO: al tocarla se pone amarilla y muestra su posición.
 */
class HandleView(context: Context) : View(context) {

    companion object {
        val AMARILLO = Color.parseColor("#FFC107")
        private val FONDO = Color.argb(179, 33, 33, 33)
    }

    // --- diagnóstico ---
    var tocada = false
    var texto: String = ""

    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FONDO }
    private val fondoTocada = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMARILLO }
    private val acento = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMARILLO }
    private val puntosTocada = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 14f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density

        canvas.drawRect(0f, 0f, w, h, if (tocada) fondoTocada else fondo)
        canvas.drawRect(0f, h - 2f * d, w, h, acento)

        val radio = 3f * d
        val separacion = 14f * d
        val cy = (h - 2f * d) / 2f
        val pincel = if (tocada) puntosTocada else acento
        for (i in -1..1) {
            canvas.drawCircle(w / 2f + i * separacion, cy, radio, pincel)
        }

        if (tocada && texto.isNotEmpty()) {
            canvas.drawText(texto, 8f * d, h / 2f + 5f * d, txt)
        }
    }
}
