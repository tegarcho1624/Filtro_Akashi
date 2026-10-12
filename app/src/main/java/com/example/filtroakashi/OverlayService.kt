package com.example.filtroakashi

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "filtro_akashi"
        private const val NOTIF_ID = 1

        // Lado del cuadrado como fracción del ANCHO de la pantalla (sin cambios).
        private const val LADO_FRACCION = 0.7f

        // Rojo puro #FF0000 al 40% de opacidad -> alfa 0.40 * 255 = 102 = 0x66 (sin cambios).
        private val COLOR = Color.argb(102, 255, 0, 0)

        // Alto de la agarradera en dp.
        private const val ALTO_AGARRADERA_DP = 40
    }

    private lateinit var windowManager: WindowManager

    // Ventana 1: el cuadrado rojo (NO recibe toques).
    private var cuadrado: View? = null
    private var paramsCuadrado: WindowManager.LayoutParams? = null

    // Ventana 2: la agarradera (SÍ recibe toques). Android aplica FLAG_NOT_TOUCHABLE
    // por ventana, no por zona, por eso son dos ventanas.
    private var agarradera: View? = null
    private var paramsAgarradera: WindowManager.LayoutParams? = null

    private var lado = 0
    private var posX = 0
    private var posY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        iniciarEnPrimerPlano()
        if (cuadrado == null) mostrarOverlay()
        return START_NOT_STICKY
    }

    private fun iniciarEnPrimerPlano() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_canal),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_texto))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun pantalla(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun altoAgarradera(): Int =
        (ALTO_AGARRADERA_DP * resources.displayMetrics.density).toInt()

    // Devuelve (alto de la barra de estado/recorte, alto de la barra de navegación inferior).
    private fun margenes(): Pair<Int, Int> {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val barraRecurso = if (id > 0) resources.getDimensionPixelSize(id) else 0
        var arriba = barraRecurso
        var abajo = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val ins = windowManager.maximumWindowMetrics.windowInsets
            val sup = ins.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            )
            val inf = ins.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
            arriba = maxOf(arriba, sup.top)
            abajo = inf.bottom
        }
        return arriba to abajo
    }

    private fun limitar() {
        val (w, h) = pantalla()
        val (barraArriba, barraAbajo) = margenes()
        posX = posX.coerceIn(0, (w - lado).coerceAtLeast(0))

        // La agarradera (borde superior del cuadrado) nunca debe quedar bajo la barra de estado.
        val minY = barraArriba
        val cabe = lado <= h - minY
        val maxY = if (cabe) h - lado else h - altoAgarradera() - barraAbajo
        posY = posY.coerceIn(minY, maxOf(minY, maxY))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun mostrarOverlay() {
        lado = (resources.displayMetrics.widthPixels * LADO_FRACCION).toInt()

        // Posición inicial: centrado.
        val (w, h) = pantalla()
        posX = (w - lado) / 2
        posY = (h - lado) / 2
        limitar()

        // --- Cuadrado rojo: no tocable ---
        val vistaCuadrado = View(this).apply { setBackgroundColor(COLOR) }
        val pc = WindowManager.LayoutParams(
            lado,
            lado,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
        }
        windowManager.addView(vistaCuadrado, pc)
        cuadrado = vistaCuadrado
        paramsCuadrado = pc

        // --- Agarradera: tocable, se añade después para quedar encima ---
        val vistaAgarradera = HandleView(this)
        val pa = WindowManager.LayoutParams(
            lado,
            altoAgarradera(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
        }
        vistaAgarradera.setOnTouchListener(crearListenerArrastre())
        windowManager.addView(vistaAgarradera, pa)
        agarradera = vistaAgarradera
        paramsAgarradera = pa
    }

    private fun crearListenerArrastre(): View.OnTouchListener {
        var inicioX = 0
        var inicioY = 0
        var toqueX = 0f
        var toqueY = 0f

        return View.OnTouchListener { vista, event ->
            val h = vista as HandleView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    h.tocada = true
                    h.texto = "DOWN"
                    h.invalidate()
                    inicioX = posX
                    inicioY = posY
                    toqueX = event.rawX
                    toqueY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    posX = inicioX + (event.rawX - toqueX).toInt()
                    posY = inicioY + (event.rawY - toqueY).toInt()
                    limitar()
                    aplicarPosicion()
                    h.texto = "x=$posX y=$posY"
                    h.invalidate()
                    true
                }
                // Al soltar, la posición simplemente se queda donde está.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.tocada = false
                    h.invalidate()
                    true
                }
                else -> false
            }
        }
    }

    private fun aplicarPosicion() {
        val c = cuadrado
        val pc = paramsCuadrado
        val a = agarradera
        val pa = paramsAgarradera
        if (c != null && pc != null && a != null && pa != null) {
            pc.x = posX; pc.y = posY
            pa.x = posX; pa.y = posY
            windowManager.updateViewLayout(c, pc)
            windowManager.updateViewLayout(a, pa)
        }
    }

    // Al rotar: recalcula tamaño, mantiene la posición dentro de los límites.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val pc = paramsCuadrado ?: return
        val pa = paramsAgarradera ?: return
        lado = (resources.displayMetrics.widthPixels * LADO_FRACCION).toInt()
        pc.width = lado; pc.height = lado
        pa.width = lado; pa.height = altoAgarradera()
        limitar()
        aplicarPosicion()
    }

    override fun onDestroy() {
        cuadrado?.let { windowManager.removeView(it) }
        agarradera?.let { windowManager.removeView(it) }
        cuadrado = null
        agarradera = null
        super.onDestroy()
    }
}
