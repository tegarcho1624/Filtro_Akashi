package com.example.filtroakashi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * Pantalla transparente: muestra el aviso del sistema para compartir pantalla
 * y le pasa el resultado al servicio.
 */
class PermisoCapturaActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, R.string.pista_captura, Toast.LENGTH_LONG).show()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACCION_ENCENDER)
                .putExtra(OverlayService.EXTRA_CODIGO, resultCode)
                .putExtra(OverlayService.EXTRA_DATOS, data)
            startForegroundService(intent)
        }
        finish()
    }
}
