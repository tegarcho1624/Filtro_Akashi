package com.example.filtroakashi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 13+: pedir permiso de notificaciones (para que se vea el aviso persistente).
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<Button>(R.id.btnPermiso).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnActivar).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.falta_permiso, Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(Intent(this, OverlayService::class.java))
            }
        }

        findViewById<Button>(R.id.btnDesactivar).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }
    }
}
