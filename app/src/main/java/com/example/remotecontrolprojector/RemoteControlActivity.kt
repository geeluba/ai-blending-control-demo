package com.example.remotecontrolprojector

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteControlActivity : AppCompatActivity() {

    private val TAG = "Projector:RemoteActivity"

    private var isBound = false
    private val _serviceFlow = MutableStateFlow<RemoteService?>(null)
    val serviceFlow: StateFlow<RemoteService?> = _serviceFlow.asStateFlow()

    // --- 1. Define Required Permissions based on Android Version ---
    private val requiredPermissions: Array<String>
        get() {
            val permissions = mutableListOf<String>()

            // Audio is needed for GGWave/Ultrasound
            permissions.add(Manifest.permission.RECORD_AUDIO)

            // Storage (Media)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                // Add VIDEO/AUDIO if needed
            } else {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            // Bluetooth & Location
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

            return permissions.toTypedArray()
        }

    // --- 2. Permission Launcher ---
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Check if ALL required permissions are granted
            val allGranted = requiredPermissions.all { permission ->
                results[permission] == true
            }

            if (allGranted) {
                Log.d(TAG, "All permissions granted. Starting Service.")
                startAndBindService()
            } else {
                Log.e(TAG, "Critical permissions denied: $results")
                showPermissionDeniedDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- 3. Gatekeeper: Check permissions before doing anything ---
        if (areAllPermissionsGranted()) {
            startAndBindService()
        } else {
            requestPermissionsLauncher.launch(requiredPermissions)
        }
    }

    private fun areAllPermissionsGranted(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startAndBindService() {
        if (isBound) return // Already running

        val serviceIntent = Intent(this, RemoteService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}")
            Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("This app requires Bluetooth, Location, and Audio permissions to connect to projectors. Please grant them in Settings.")
            .setPositiveButton("Retry") { _, _ ->
                requestPermissionsLauncher.launch(requiredPermissions)
            }
            .setNegativeButton("Close App") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isBound = true
            val binder = service as? RemoteService.RemoteBinder ?: return
            _serviceFlow.value = binder.getService()
            Log.d(TAG, "RemoteService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            _serviceFlow.value = null
            Log.d(TAG, "RemoteService disconnected")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}