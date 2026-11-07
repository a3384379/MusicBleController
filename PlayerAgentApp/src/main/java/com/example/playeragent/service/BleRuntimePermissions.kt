package com.example.playeragent.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal object BleRuntimePermissions {
    fun granted(context: Context): Boolean = granted(Build.VERSION.SDK_INT) {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    // These permission names are inlined String constants, safe on older APIs.
    // Lint cannot infer the SDK guard from the version injected by JVM tests.
    @SuppressLint("InlinedApi")
    fun granted(sdkInt: Int, hasPermission: (String) -> Boolean): Boolean =
        if (sdkInt >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
