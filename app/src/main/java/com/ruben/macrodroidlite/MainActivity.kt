package com.ruben.macrodroidlite

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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
        val steps = mutableListOf<Pair<String, () -> Unit>>()

        // Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            missing.add("Mostrar sobre otras apps")
            steps.add("Activar overlay" to {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            })
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                missing.add("Notificaciones")
                steps.add("Permitir notificaciones" to {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST
                    )
                })
            }
        }

        // Accessibility service check
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        if (!accessibilityEnabled) {
            missing.add("Servicio de Accesibilidad")
            steps.add("Activar accesibilidad" to {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }

        if (missing.isEmpty()) {
            startOverlayService()
            finish()
            return
        }

        showPermissionDialog(missing, steps)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun showPermissionDialog(
        missing: List<String>,
        steps: List<Pair<String, () -> Unit>>
    ) {
        val stepDescriptions = missing.mapIndexed { i, name ->
            "${i + 1}. $name"
        }

        AlertDialog.Builder(this)
            .setTitle("🛠️ Configuración inicial")
            .setMessage(
                "La app necesita estos permisos para funcionar:\n\n" +
                stepDescriptions.joinToString("\n") + "\n\n" +
                "Pulsa \"Siguiente\" para ir al primer paso."
            )
            .setPositiveButton("Siguiente →") { _, _ ->
                openNextStep(steps, 0)
            }
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun openNextStep(steps: List<Pair<String, () -> Unit>>, index: Int) {
        if (index >= steps.size) {
            // Todos los permisos concedidos, comprobar de nuevo
            checkPermissionsAndStart()
            return
        }

        val (name, action) = steps[index]

        AlertDialog.Builder(this)
            .setTitle("Paso ${index + 1} de ${steps.size}")
            .setMessage(name)
            .setPositiveButton("Abrir ajustes") { _, _ ->
                action()
                // Mostrar siguiente paso al volver
            }
            .setNegativeButton("Ya lo hice") { _, _ ->
                openNextStep(steps, index + 1)
            }
            .setCancelable(false)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Al volver de permisos, comprobar estado
        checkPermissionsAndStart()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkPermissionsAndStart()
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
