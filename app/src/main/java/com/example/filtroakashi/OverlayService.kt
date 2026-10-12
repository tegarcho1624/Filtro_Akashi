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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        // Acciones que envía la pantalla principal.
        const val ACCION_ENCENDER = "com.example.filtroakashi.ENCENDER"
        const val ACCION_APAGAR = "com.example.filtroakashi.APAGAR"

        private const val CHANNEL_ID = "filtro_akashi"
        private const val CHANNEL_MIN_ID = "filtro_akashi_min"
        private const val NOTIF_ID = 1

        // true  = con el filtro apagado la notificación desaparece del todo.
        // false = con el filtro apagado queda una notificación mínima (sin texto).
        // Si Android o Lenovo cierran la burbuja con el filtro apagado, pon esto en false.
        private const val OCULTAR_NOTIFICACION_AL_APAGAR = true

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

        // Burbuja flotante.
        private const val BURBUJA_DP = 48
        private const val CLAVE_BURBUJA_X = "burbuja_x"
        private const val CLAVE_BURBUJA_Y = "burbuja_y"
        private const val ALPHA_BORDE = 0.2f      // opacidad de la burbuja pegada al borde
        private const val ZONA_BORDE_DP = 32      // a esta distancia del borde ya es 100% visible
    }

    private enum class Esquina { SUP_IZQ, SUP_DER, INF_IZQ, INF_DER }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    // ----- Estado -----
    private var filtroActivo = false
    private var rectInicializado = false

    // ----- Burbuja (siempre visible mientras el servicio vive) -----
    private var burbuja: BubbleView? = null
    private var paramsBurbuja: WindowManager.LayoutParams? = null
    private var burbujaEnPantalla = false

    // ----- Ventanas del filtro (solo mientras está activo) -----
    // El cuadrado rojo (NO recibe toques).
    private var cuadrado: View? = null
    private var paramsCuadrado: WindowManager.LayoutParams? = null

    // La agarradera (SÍ recibe toques). Mueve el cuadrado entero.
    private var agarradera: View? = null
    private var paramsAgarradera: WindowManager.LayoutParams? = null

    // Las cuatro pestañas de esquina (SÍ reciben toques).
    private val pestanas = mutableMapOf<Esquina, Pair<View, WindowManager.LayoutParams>>()

    // Ícono que abre el panel de opacidad (SÍ recibe toques).
    private var icono: View? = null
    private var paramsIcono: WindowManager.LayoutParams? = null

    // Panel de opacidad, solo mientras está abierto (SÍ recibe toques).
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
        crearCanales()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_APAGAR -> {
                if (filtroActivo) {
                    // Apaga el filtro pero deja la burbuja.
                    desactivarFiltro()
                    actualizarNotificacion()
                } else {
                    // Ya estaba apagado: cierra todo (la burbuja desaparece).
                    stopSelf()
                }
            }
            else -> {
                // ACCION_ENCENDER (o inicio sin acción).
                crearBurbujaSiHaceFalta()
                if (!filtroActivo) activarFiltro()
                actualizarNotificacion()
            }
        }
        return START_NOT_STICKY
    }

    // ---------- Notificación / Foreground Service ----------

    private fun crearCanales() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_canal),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MIN_ID,
                getString(R.string.notif_canal_min),
                NotificationManager.IMPORTANCE_MIN
            )
        )
    }

    private fun entrarEnPrimerPlano(notif: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // Si Android no permite pasar a primer plano desde segundo plano,
            // el filtro y la burbuja siguen funcionando, solo que sin notificación.
        }
    }

    /**
     * Filtro activo  -> notificación "Filtro Akashi activo" (primer plano).
     * Filtro apagado -> sin notificación (el servicio sigue vivo sostenido por la burbuja),
     *                   o una notificación mínima si OCULTAR_NOTIFICACION_AL_APAGAR = false.
     */
    private fun actualizarNotificacion() {
        if (filtroActivo) {
            val notif = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_texto))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            entrarEnPrimerPlano(notif)
        } else if (OCULTAR_NOTIFICACION_AL_APAGAR) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            val minima = Notification.Builder(this, CHANNEL_MIN_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            entrarEnPrimerPlano(minima)
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

    // ---------- Geometría del filtro ----------

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

    // ---------- Activar / desactivar el filtro ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun activarFiltro() {
        if (filtroActivo) return

        // Carga la opacidad guardada (40% la primera vez).
        opacidad = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(CLAVE_OPACIDAD, OPACIDAD_DEFECTO)
            .coerceIn(OPACIDAD_MIN, OPACIDAD_MAX)

        // La primera vez: centrado con el tamaño inicial de siempre.
        // Después, mientras el servicio viva, reaparece donde lo dejaste.
        if (!rectInicializado) {
            val lado = (resources.displayMetrics.widthPixels * LADO_FRACCION).toInt()
            val (w, h) = pantalla()
            izq = (w - lado) / 2
            arr = (h - lado) / 2
            der = izq + lado
            aba = arr + lado
            moverA(izq, arr)
            rectInicializado = true
        }

        // La burbuja se quita un momento para volver a ponerla al final y que quede encima.
        quitarBurbujaDePantalla()

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

        // 3) Cuatro pestañas de esquina: tocables.
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

        filtroActivo = true
        burbuja?.activo = true
        ponerBurbujaEnPantalla()
    }

    private fun desactivarFiltro() {
        if (!filtroActivo) return
        ocultarPanel()
        icono?.let { windowManager.removeView(it) }
        cuadrado?.let { windowManager.removeView(it) }
        agarradera?.let { windowManager.removeView(it) }
        for ((_, par) in pestanas) windowManager.removeView(par.first)
        pestanas.clear()
        icono = null
        paramsIcono = null
        cuadrado = null
        paramsCuadrado = null
        agarradera = null
        paramsAgarradera = null
        filtroActivo = false
        burbuja?.activo = false
    }

    private fun alternarFiltro() {
        if (filtroActivo) desactivarFiltro() else activarFiltro()
        actualizarNotificacion()
    }

    // ---------- Burbuja flotante ----------

    private fun tamanoBurbuja(): Int = dp(BURBUJA_DP)

    @SuppressLint("ClickableViewAccessibility")
    private fun crearBurbujaSiHaceFalta() {
        if (burbuja != null) return
        val tam = tamanoBurbuja()
        val (w, h) = pantalla()
        val (barraArriba, _) = margenes()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        // Posición guardada; la primera vez: borde derecho, a un tercio de la altura.
        val x = prefs.getInt(CLAVE_BURBUJA_X, w - tam - dp(40))
        val y = prefs.getInt(CLAVE_BURBUJA_Y, h / 3)
        val p = parametros(
            x.coerceIn(0, maxOf(0, w - tam)),
            y.coerceIn(barraArriba, maxOf(barraArriba, h - tam)),
            tam, tam, tocable = true
        )

        val vista = BubbleView(this)
        vista.activo = filtroActivo
        vista.setOnTouchListener(crearListenerBurbuja())
        burbuja = vista
        paramsBurbuja = p
        ponerBurbujaEnPantalla()
    }

    private fun ponerBurbujaEnPantalla() {
        val b = burbuja ?: return
        val p = paramsBurbuja ?: return
        if (burbujaEnPantalla) return
        windowManager.addView(b, p)
        burbujaEnPantalla = true
        actualizarAlphaBurbuja()
    }

    private fun quitarBurbujaDePantalla() {
        val b = burbuja ?: return
        if (!burbujaEnPantalla) return
        windowManager.removeViewImmediate(b)
        burbujaEnPantalla = false
    }

    // Distancia al borde más cercano -> opacidad: 20% pegada al borde, 100% a ZONA_BORDE_DP o más.
    private fun actualizarAlphaBurbuja() {
        val b = burbuja ?: return
        val p = paramsBurbuja ?: return
        val tam = tamanoBurbuja()
        val (w, h) = pantalla()
        val (barraArriba, _) = margenes()
        val distancia = minOf(p.x, p.y - barraArriba, w - (p.x + tam), h - (p.y + tam))
            .coerceAtLeast(0)
        val fraccion = minOf(distancia.toFloat() / dp(ZONA_BORDE_DP), 1f)
        b.alpha = ALPHA_BORDE + (1f - ALPHA_BORDE) * fraccion
    }

    private fun guardarPosicionBurbuja() {
        val p = paramsBurbuja ?: return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(CLAVE_BURBUJA_X, p.x)
            .putInt(CLAVE_BURBUJA_Y, p.y)
            .apply()
    }

    private fun abrirAppPrincipal() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Si Android bloquea abrir la app desde segundo plano, simplemente no se abre.
        }
    }

    // Toque corto: activa/desactiva el filtro. Arrastre: mueve la burbuja.
    // Mantener presionado (sin arrastrar): abre la app principal.
    private fun crearListenerBurbuja(): View.OnTouchListener {
        var inicioX = 0
        var inicioY = 0
        var toqueX = 0f
        var toqueY = 0f
        var arrastrando = false
        var largo = false
        val umbral = ViewConfiguration.get(this).scaledTouchSlop

        val alMantener = Runnable {
            largo = true
            burbuja?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            abrirAppPrincipal()
        }

        return View.OnTouchListener { vista, event ->
            val p = paramsBurbuja
            if (p == null) {
                false
            } else when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    inicioX = p.x
                    inicioY = p.y
                    toqueX = event.rawX
                    toqueY = event.rawY
                    arrastrando = false
                    largo = false
                    vista.alpha = 1f // al tocarla vuelve a la normalidad
                    handler.postDelayed(alMantener, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - toqueX
                    val dy = event.rawY - toqueY
                    if (!arrastrando && Math.hypot(dx.toDouble(), dy.toDouble()) > umbral) {
                        arrastrando = true
                        handler.removeCallbacks(alMantener)
                    }
                    if (arrastrando && !largo) {
                        val tam = tamanoBurbuja()
                        val (w, h) = pantalla()
                        val (barraArriba, _) = margenes()
                        p.x = (inicioX + dx.toInt()).coerceIn(0, maxOf(0, w - tam))
                        p.y = (inicioY + dy.toInt()).coerceIn(barraArriba, maxOf(barraArriba, h - tam))
                        windowManager.updateViewLayout(vista, p)
                        actualizarAlphaBurbuja()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(alMantener)
                    if (arrastrando) {
                        guardarPosicionBurbuja()
                    } else if (!largo) {
                        // Se hace después de terminar este toque para poder reordenar ventanas.
                        handler.post { alternarFiltro() }
                    }
                    actualizarAlphaBurbuja()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(alMantener)
                    actualizarAlphaBurbuja()
                    true
                }
                else -> true
            }
        }
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

    // ---------- Toques del filtro ----------

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

    // Al rotar: cierra el panel, ajusta el filtro a la nueva pantalla y mantiene la burbuja dentro.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (panel != null) ocultarPanel()
        if (rectInicializado) {
            normalizar()
            aplicarGeometria()
        }
        val p = paramsBurbuja
        val b = burbuja
        if (p != null && b != null && burbujaEnPantalla) {
            val tam = tamanoBurbuja()
            val (w, h) = pantalla()
            val (barraArriba, _) = margenes()
            p.x = p.x.coerceIn(0, maxOf(0, w - tam))
            p.y = p.y.coerceIn(barraArriba, maxOf(barraArriba, h - tam))
            windowManager.updateViewLayout(b, p)
            actualizarAlphaBurbuja()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        desactivarFiltro()
        quitarBurbujaDePantalla()
        burbuja = null
        super.onDestroy()
    }
}
