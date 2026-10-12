package com.example.filtroakashi

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
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "filtro_akashi"
        private const val NOTIF_ID = 1

        // Lado del cuadrado como fracción del ANCHO de la pantalla. Cámbialo a tu gusto.
        private const val LADO_FRACCION = 0.7f

        // Rojo puro #FF0000 al 40% de opacidad -> alfa 0.40 * 255 = 102 = 0x66.
        private val COLOR = Color.argb(102, 255, 0, 0)
    }

    private lateinit var windowManager: WindowManager
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        iniciarEnPrimerPlano()
        if (overlay == null) mostrarOverlay()
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

    private fun mostrarOverlay() {
        val view = View(this).apply { setBackgroundColor(COLOR) }
        windowManager.addView(view, crearParametros())
        overlay = view
    }

    private fun crearParametros(): WindowManager.LayoutParams {
        val lado = (resources.displayMetrics.widthPixels * LADO_FRACCION).toInt()
        return WindowManager.LayoutParams(
            lado,
            lado,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
    }

    // Si se rota la pantalla, recalcula el tamaño para mantenerlo centrado y proporcionado.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.let { windowManager.updateViewLayout(it, crearParametros()) }
    }

    override fun onDestroy() {
        overlay?.let { windowManager.removeView(it) }
        overlay = null
        super.onDestroy()
    }
}
