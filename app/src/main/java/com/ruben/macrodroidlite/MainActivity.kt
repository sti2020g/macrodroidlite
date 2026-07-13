package com.ruben.macrodroidlite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 100
        private const val NOTIFICATION_PERMISSION_REQUEST = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val missing = mutableListOf<String>()

        // Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            missing.add("Mostrar sobre otras apps")
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                missing.add("Notificaciones")
            }
        }

        if (missing.isEmpty()) {
            startOverlayService()
            finish()
            return
        }

        showPermissionDialog(missing)
    }

    private fun showPermissionDialog(missing: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Permisos necesarios")
            .setMessage(
                "La app necesita estos permisos para funcionar:\n\n" +
                missing.joinToString("\n") { "• $it" } + "\n\n" +
                "También debes activar el Servicio de Accesibilidad en:\n" +
                "Ajustes → Accesibilidad → MacroDroid Lite"
            )
            .setPositiveButton("Ir a permisos") { _, _ ->
                if (missing.any { it.contains("sobre") }) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
                }
                if (Build.VERSION.SDK_INT >= 33 && missing.any { it.contains("Notif") }) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST
                    )
                }
            }
            .setNegativeButton("Salir") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
