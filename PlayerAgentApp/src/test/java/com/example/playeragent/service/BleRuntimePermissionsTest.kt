package com.example.playeragent.service

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleRuntimePermissionsTest {
    @Test
    fun android12AndLaterRequireBothConnectAndAdvertise() {
        val connect = Manifest.permission.BLUETOOTH_CONNECT
        val advertise = Manifest.permission.BLUETOOTH_ADVERTISE
        for (sdk in listOf(31, 35)) {
            assertFalse(BleRuntimePermissions.granted(sdk) { false })
            assertFalse(BleRuntimePermissions.granted(sdk) { it == connect })
            assertFalse(BleRuntimePermissions.granted(sdk) { it == advertise })
            assertTrue(BleRuntimePermissions.granted(sdk) { it == connect || it == advertise })
        }
    }

    @Test
    fun olderAndroidUsesFineLocation() {
        for (sdk in listOf(23, 30)) {
            assertFalse(BleRuntimePermissions.granted(sdk) { false })
            assertFalse(BleRuntimePermissions.granted(sdk) { it == Manifest.permission.ACCESS_COARSE_LOCATION })
            assertTrue(BleRuntimePermissions.granted(sdk) { it == Manifest.permission.ACCESS_FINE_LOCATION })
        }
    }
}
