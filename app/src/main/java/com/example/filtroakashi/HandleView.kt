package com.example.filtroakashi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Agarradera visual: barra delgada semitransparente con tres puntitos amarillos
 * y una línea fina amarilla abajo. Es la única parte del filtro que recibe toques.
 */
class HandleView(context: Context) : View(context) {

    companion object {
        // Amarillo de acento (cámbialo aquí si en tus otras apps usas otro tono).
        val AMARILLO = Color.parseColor("#FFC107")
        private val FONDO = Color.argb(179, 33, 33, 33) // gris oscuro al ~70%
    }

    private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FONDO }
    private val acento = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMARILLO }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density

        canvas.drawRect(0f, 0f, w, h, fondo)

        // Línea fina amarilla en el borde inferior de la agarradera.
        canvas.drawRect(0f, h - 2f * d, w, h, acento)

        // Tres puntitos centrados.
        val radio = 3f * d
        val separacion = 14f * d
        val cy = (h - 2f * d) / 2f
        for (i in -1..1) {
            canvas.drawCircle(w / 2f + i * separacion, cy, radio, acento)
        }
    }
}
