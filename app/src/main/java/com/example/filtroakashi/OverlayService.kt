package com.example.filtroakashi

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "filtro_akashi"
        private const val NOTIF_ID = 1

        // Tamaño inicial: lado = fracción del ANCHO de la pantalla (sin cambios).
        private const val LADO_FRACCION = 0.7f

        private const val ALTO_AGARRADERA_DP = 40
        private const val PESTANA_DP = 36

        // Ancho y alto mínimos al redimensionar, en píxeles.
        private const val MIN_PX = 50

        // Opacidad del rojo (#FF0000), en porcentaje.
        private const val OPACIDAD_MIN = 10
        private const val OPACIDAD_MAX = 90
        private const val OPACIDAD_DEFECTO = 40
        private const val PREFS = "filtro_akashi_prefs"
        private const val CLAVE_OPACIDAD = "opacidad"

        private const val PANEL_ANCHO_DP = 280
        private const val PANEL_ALTO_DP = 130
    }

    private enum class Esquina { SUP_IZQ, SUP_DER, INF_IZQ, INF_DER }

    private lateinit var windowManager: WindowManager

    // Ventana 1: el cuadrado rojo (NO recibe toques).
    private var cuadrado: View? = null
    private var paramsCuadrado: WindowManager.LayoutParams? = null

    // Ventana 2: la agarradera (SÍ recibe toques). Mueve el cuadrado entero.
    private var agarradera: View? = null
    private var paramsAgarradera: WindowManager.LayoutParams? = null

    // Ventanas 3 a 6: las cuatro pestañas de esquina (SÍ reciben toques).
    private val pestanas = mutableMapOf<Esquina, Pair<View, WindowManager.LayoutParams>>()

    // Ventana 7: ícono que abre el panel de opacidad (SÍ recibe toques).
    private var icono: View? = null
    private var paramsIcono: WindowManager.LayoutParams? = null

    // Ventana 8: panel de opacidad, solo mientras está abierto (SÍ recibe toques).
    private var panel: View? = null

    // Rectángulo actual del filtro, en píxeles de pantalla.
    private var izq = 0
    private var arr = 0
    private var der = 0
    private var aba = 0

    // Opacidad actual en porcentaje (10..90).
    private var opacidad = OPACIDAD_DEFECTO

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

    // ---------- Medidas de pantalla ----------

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

    // (alto de la barra de estado/recorte, alto de la barra de navegación inferior)
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

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    private fun altoAgarradera(): Int = dp(ALTO_AGARRADERA_DP)

    private fun tamanoPestana(): Int = dp(PESTANA_DP)

    // ---------- Geometría ----------

    /** Mueve el rectángulo entero a (nx, ny) conservando su tamaño, respetando límites. */
    private fun moverA(nx: Int, ny: Int) {
        val ancho = der - izq
        val alto = aba - arr
        val (w, h) = pantalla()
        val (barraArriba, barraAbajo) = margenes()

        val x = nx.coerceIn(0, maxOf(0, w - ancho))
        // La agarradera nunca queda bajo la barra de estado.
        val cabe = alto <= h - barraArriba
        val maxY = if (cabe) h - alto else h - altoAgarradera() - barraAbajo
        val y = ny.coerceIn(barraArriba, maxOf(barraArriba, maxY))

        izq = x; arr = y; der = x + ancho; aba = y + alto
    }

    /** Mete el rectángulo dentro de la pantalla (lo encoge si hace falta). */
    private fun normalizar() {
        val (w, h) = pantalla()
        val (barraArriba, _) = margenes()
        izq = izq.coerceIn(0, w - MIN_PX)
        arr = arr.coerceIn(barraArriba, h - MIN_PX)
        der = der.coerceIn(izq + MIN_PX, w)
        aba = aba.coerceIn(arr + MIN_PX, h)
    }

    private fun posicionPestana(e: Esquina): Pair<Int, Int> {
        val t = tamanoPestana()
        val (barraArriba, _) = margenes()
        val (x, y) = when (e) {
            Esquina.SUP_IZQ -> izq to arr
            Esquina.SUP_DER -> (der - t) to arr
            Esquina.INF_IZQ -> izq to (aba - t)
            Esquina.INF_DER -> (der - t) to (aba - t)
        }
        return x.coerceAtLeast(0) to y.coerceAtLeast(barraArriba)
    }

    // El ícono va en la agarradera, justo a la derecha de la pestaña superior-izquierda.
    private fun posicionIcono(): Pair<Int, Int> {
        val t = tamanoPestana()
        val (w, _) = pantalla()
        val (barraArriba, _) = margenes()
        val x = (izq + t).coerceIn(0, maxOf(0, w - t))
        return x to arr.coerceAtLeast(barraArriba)
    }

    private fun aplicarGeometria() {
        val c = cuadrado ?: return
        val pc = paramsCuadrado ?: return
        val a = agarradera ?: return
        val pa = paramsAgarradera ?: return

        pc.x = izq; pc.y = arr; pc.width = der - izq; pc.height = aba - arr
        pa.x = izq; pa.y = arr; pa.width = der - izq; pa.height = altoAgarradera()
        windowManager.updateViewLayout(c, pc)
        windowManager.updateViewLayout(a, pa)

        val t = tamanoPestana()
        for ((e, par) in pestanas) {
            val (x, y) = posicionPestana(e)
            par.second.x = x; par.second.y = y; par.second.width = t; par.second.height = t
            windowManager.updateViewLayout(par.first, par.second)
        }

        val ic = icono
        val pi = paramsIcono
        if (ic != null && pi != null) {
            val (x, y) = posicionIcono()
            pi.x = x; pi.y = y; pi.width = t; pi.height = t
            windowManager.updateViewLayout(ic, pi)
        }
    }

    // ---------- Opacidad ----------

    // Rojo puro #FF0000 con la opacidad elegida. Se cambia el color del fondo del cuadrado
    // (no el alpha de la ventana), así el alpha de la ventana sigue igual que en las fases anteriores.
    private fun colorRojo(): Int = Color.argb(opacidad * 255 / 100, 255, 0, 0)

    private fun guardarOpacidad() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(CLAVE_OPACIDAD, opacidad).apply()
    }

    // ---------- Ventanas ----------

    private fun parametros(x: Int, y: Int, ancho: Int, alto: Int, tocable: Boolean):
        WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!tocable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            ancho,
            alto,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun mostrarOverlay() {
        // Carga la opacidad guardada (40% la primera vez).
        opacidad = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(CLAVE_OPACIDAD, OPACIDAD_DEFECTO)
            .coerceIn(OPACIDAD_MIN, OPACIDAD_MAX)

        val lado = (resources.displayMetrics.widthPixels * LADO_FRACCION).toInt()

        // Posición inicial: centrado (tamaño y posición inicial sin cambios).
        val (w, h) = pantalla()
        izq = (w - lado) / 2
        arr = (h - lado) / 2
        der = izq + lado
        aba = arr + lado
        moverA(izq, arr)

        // 1) Cuadrado rojo: no tocable.
        val vistaCuadrado = View(this).apply { setBackgroundColor(colorRojo()) }
        val pc = parametros(izq, arr, der - izq, aba - arr, tocable = false)
        windowManager.addView(vistaCuadrado, pc)
        cuadrado = vistaCuadrado
        paramsCuadrado = pc

        // 2) Agarradera: tocable, encima del cuadrado.
        val vistaAgarradera = HandleView(this)
        val pa = parametros(izq, arr, der - izq, altoAgarradera(), tocable = true)
        vistaAgarradera.setOnTouchListener(crearListenerArrastre())
        windowManager.addView(vistaAgarradera, pa)
        agarradera = vistaAgarradera
        paramsAgarradera = pa

        // 3) Cuatro pestañas de esquina: tocables, encima de todo.
        val t = tamanoPestana()
        for (e in Esquina.values()) {
            val vista = TabView(this)
            val (x, y) = posicionPestana(e)
            val p = parametros(x, y, t, t, tocable = true)
            vista.setOnTouchListener(crearListenerEsquina(e))
            windowManager.addView(vista, p)
            pestanas[e] = vista to p
        }

        // 4) Ícono que abre el panel de opacidad.
        val vistaIcono = IconView(this)
        val (ix, iy) = posicionIcono()
        val pi = parametros(ix, iy, t, t, tocable = true)
        vistaIcono.setOnTouchListener(crearListenerIcono())
        windowManager.addView(vistaIcono, pi)
        icono = vistaIcono
        paramsIcono = pi
    }

    // ---------- Panel de opacidad ----------

    private fun alternarPanel() {
        if (panel == null) mostrarPanel() else ocultarPanel()
    }

    private fun mostrarPanel() {
        if (panel != null) return
        val (w, h) = pantalla()
        val (barraArriba, barraAbajo) = margenes()
        val anchoPanel = minOf(dp(PANEL_ANCHO_DP), w)
        val altoPanel = dp(PANEL_ALTO_DP)
        val sep = dp(8)

        // Debajo de la agarradera; si no cabe, encima. Siempre dentro de la pantalla.
        val x = izq.coerceIn(0, maxOf(0, w - anchoPanel))
        var y = arr + altoAgarradera() + sep
        if (y + altoPanel > h - barraAbajo) y = arr - altoPanel - sep
        y = y.coerceIn(barraArriba, maxOf(barraArriba, h - barraAbajo - altoPanel))

        val vista = crearPanel()
        windowManager.addView(vista, parametros(x, y, anchoPanel, altoPanel, tocable = true))
        panel = vista
    }

    private fun ocultarPanel() {
        panel?.let { windowManager.removeView(it) }
        panel = null
        guardarOpacidad()
    }

    private fun crearPanel(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 33, 33, 33))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), HandleView.AMARILLO)
            }
        }

        val titulo = TextView(this).apply {
            text = "Opacidad"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valor = TextView(this).apply {
            text = "$opacidad%"
            setTextColor(HandleView.AMARILLO)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val cerrar = TextView(this).apply {
            text = "✕"
            setTextColor(HandleView.AMARILLO)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(4), dp(6), dp(4))
            setOnClickListener { ocultarPanel() }
        }
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titulo)
            addView(valor)
            addView(cerrar)
        }

        val deslizador = SeekBar(this).apply {
            max = OPACIDAD_MAX - OPACIDAD_MIN
            progress = opacidad - OPACIDAD_MIN
            val amarillo = ColorStateList.valueOf(HandleView.AMARILLO)
            progressTintList = amarillo
            thumbTintList = amarillo
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(160, 160, 160, 160))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progreso: Int, delUsuario: Boolean) {
                    opacidad = OPACIDAD_MIN + progreso
                    valor.text = "$opacidad%"
                    // Cambio en tiempo real, sin reiniciar el filtro.
                    cuadrado?.setBackgroundColor(colorRojo())
                    if (delUsuario) guardarOpacidad()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { guardarOpacidad() }
            })
        }

        raiz.addView(fila)
        raiz.addView(
            deslizador,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        return raiz
    }

    // ---------- Toques ----------

    // Ícono: un toque corto abre/cierra el panel.
    private fun crearListenerIcono(): View.OnTouchListener {
        return View.OnTouchListener { vista, event ->
            val ic = vista as IconView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ic.tocada = true
                    ic.invalidate()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    ic.tocada = false
                    ic.invalidate()
                    val dentro = event.x in 0f..vista.width.toFloat() &&
                        event.y in 0f..vista.height.toFloat()
                    if (dentro) alternarPanel()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    ic.tocada = false
                    ic.invalidate()
                    true
                }
                else -> true
            }
        }
    }

    // Agarradera: mueve el cuadrado entero sin cambiar su tamaño (comportamiento igual que antes).
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
                    inicioX = izq
                    inicioY = arr
                    toqueX = event.rawX
                    toqueY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    moverA(
                        inicioX + (event.rawX - toqueX).toInt(),
                        inicioY + (event.rawY - toqueY).toInt()
                    )
                    aplicarGeometria()
                    h.texto = "x=$izq y=$arr"
                    h.invalidate()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.tocada = false
                    h.invalidate()
                    true
                }
                else -> false
            }
        }
    }

    // Pestañas: mueven su esquina; la esquina opuesta queda fija.
    private fun crearListenerEsquina(esquina: Esquina): View.OnTouchListener {
        var sIzq = 0
        var sArr = 0
        var sDer = 0
        var sAba = 0
        var toqueX = 0f
        var toqueY = 0f
        var anchoPantalla = 0
        var altoPantalla = 0
        var minY = 0

        return View.OnTouchListener { vista, event ->
            val tab = vista as TabView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tab.tocada = true
                    tab.invalidate()
                    // Si el cuadrado inicial no cabía en pantalla, se ajusta al primer toque.
                    normalizar()
                    aplicarGeometria()
                    val (w, h) = pantalla()
                    anchoPantalla = w
                    altoPantalla = h
                    minY = margenes().first
                    sIzq = izq; sArr = arr; sDer = der; sAba = aba
                    toqueX = event.rawX
                    toqueY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - toqueX).toInt()
                    val dy = (event.rawY - toqueY).toInt()
                    when (esquina) {
                        Esquina.SUP_IZQ -> {
                            izq = (sIzq + dx).coerceIn(0, sDer - MIN_PX)
                            arr = (sArr + dy).coerceIn(minY, sAba - MIN_PX)
                        }
                        Esquina.SUP_DER -> {
                            der = (sDer + dx).coerceIn(sIzq + MIN_PX, anchoPantalla)
                            arr = (sArr + dy).coerceIn(minY, sAba - MIN_PX)
                        }
                        Esquina.INF_IZQ -> {
                            izq = (sIzq + dx).coerceIn(0, sDer - MIN_PX)
                            aba = (sAba + dy).coerceIn(sArr + MIN_PX, altoPantalla)
                        }
                        Esquina.INF_DER -> {
                            der = (sDer + dx).coerceIn(sIzq + MIN_PX, anchoPantalla)
                            aba = (sAba + dy).coerceIn(sArr + MIN_PX, altoPantalla)
                        }
                    }
                    aplicarGeometria()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tab.tocada = false
                    tab.invalidate()
                    true
                }
                else -> false
            }
        }
    }

    // Al rotar: cierra el panel, conserva el rectángulo y lo ajusta a la nueva pantalla.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (cuadrado == null) return
        ocultarPanel()
        normalizar()
        aplicarGeometria()
    }

    override fun onDestroy() {
        ocultarPanel()
        icono?.let { windowManager.removeView(it) }
        cuadrado?.let { windowManager.removeView(it) }
        agarradera?.let { windowManager.removeView(it) }
        for ((_, par) in pestanas) windowManager.removeView(par.first)
        pestanas.clear()
        icono = null
        cuadrado = null
        agarradera = null
        super.onDestroy()
    }
}
