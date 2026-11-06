package com.example.playeragent.service

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.playeragent.debug.PlayerAgentDebugControlReceiver
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Run denied cases with CONNECT/ADVERTISE/SCAN revoked before instrumentation.
 * Grant those three permissions externally for the granted case. Revoking a
 * target permission during instrumentation kills its process, so this fixture
 * deliberately does not change device permission state itself.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class BleServicePermissionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun stopPreviousServiceAndResetLogs() {
        context.stopService(serviceIntent())
        await("previous service did not stop") { !serviceRegistered() }
        shell("logcat -c")
    }

    @After
    fun stopService() {
        context.stopService(serviceIntent())
        await("test service did not stop") { !serviceRegistered() }
    }

    @Test
    fun deniedDebugActionsDoNotColdStartAServiceAndStopRemainsAvailable() {
        requireMissingPermissions()
        for (action in listOf(
            PlayerAgentDebugControlReceiver.ACTION_START_BLE_SERVICE,
            PlayerAgentDebugControlReceiver.ACTION_RECOVER_BLE_STACK,
            PlayerAgentDebugControlReceiver.ACTION_DUMP_BLE_DIAGNOSTICS,
            PlayerAgentDebugControlReceiver.ACTION_REFRESH_CURRENT_LYRIC
        )) {
            broadcast(action)
            val shortAction = action.substringAfterLast('.')
            assertTrue(logs().contains("skipped action=$shortAction reason=permission_missing"))
        }
        broadcast(PlayerAgentDebugControlReceiver.ACTION_STOP_BLE_SERVICE)
        assertTrue(logs().contains("completed action=STOP_BLE_SERVICE stopped=false"))
        assertFalse(logs().contains("forwarded to service"))
        assertFalse(logs().contains("Foreground service started"))
        assertFalse(PlayerAgentForegroundService.isRunning())
        assertFalse(serviceRegistered())
        assertNoFatal()
    }

    @Test
    fun deniedDirectForegroundStartStopsBeyondTheSystemDeadlineWithoutRestart() {
        requireMissingPermissions()
        // Bypass the receiver guard to exercise the real Service.onCreate catch.
        context.startForegroundService(serviceIntent().setAction(
            PlayerAgentForegroundService.ACTION_RECOVER_BLE_STACK
        ))
        await("service did not report the platform permission rejection") {
            logs().contains("failed reason=foreground_permission_denied")
        }
        await("rejected foreground service remained registered") { !serviceRegistered() }
        // Android's foreground-promotion deadline must not crash the process
        // later, and START_NOT_STICKY must not leave an active/restarting service.
        val settings = shell("dumpsys activity settings")
        val timeoutMs = requireNotNull(Regex("(?m)^\\s*service_start_foreground_timeout_ms=(\\d+)\\s*$")
            .find(settings)?.groupValues?.get(1)?.toLong()) {
            "Cannot verify the platform foreground-promotion deadline from activity settings"
        }
        SystemClock.sleep(timeoutMs + 2_000L)
        assertFalse(PlayerAgentForegroundService.isRunning())
        assertFalse(serviceRegistered())
        val output = logs()
        assertFalse(output.contains("Foreground service started"))
        assertFalse(output.contains("[BleHealth] recover start"))
        assertFalse(output.contains("ForegroundServiceDidNotStartInTimeException"))
        assertNoFatal()
    }

    @Test
    fun grantedBlePermissionsAllowColdRecoveryWithoutNotificationPermission() {
        assumeTrue("CONNECT must be granted before this case", granted(Manifest.permission.BLUETOOTH_CONNECT))
        assumeTrue("ADVERTISE must be granted before this case", granted(Manifest.permission.BLUETOOTH_ADVERTISE))
        assumeFalse("POST_NOTIFICATIONS must remain denied to test the FGS contract",
            granted(Manifest.permission.POST_NOTIFICATIONS))
        broadcast(PlayerAgentDebugControlReceiver.ACTION_RECOVER_BLE_STACK)
        await("granted recovery did not start a foreground service") {
            PlayerAgentForegroundService.isRunning() && logs().contains("[BleHealth] recover done")
        }
        assertTrue(serviceRegistered())
        assertTrue(logs().contains("[BleHealth] recover start"))
        assertFalse(logs().contains("reason=foreground_permission_denied"))
        assertNoFatal()
    }

    private fun requireMissingPermissions() {
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)) {
            assumeFalse("Revoke $permission before running denied cases", granted(permission))
        }
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun serviceIntent() = Intent(context, PlayerAgentForegroundService::class.java)

    @Suppress("DEPRECATION")
    private fun serviceRegistered(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any {
            it.service.className == PlayerAgentForegroundService::class.java.name
        }

    private fun broadcast(action: String) {
        val finished = CountDownLatch(1)
        context.sendOrderedBroadcast(Intent(action).setComponent(
            ComponentName(context, PlayerAgentDebugControlReceiver::class.java)
        ), null, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { finished.countDown() }
        }, null, 0, null, null)
        assertTrue("broadcast did not complete: $action", finished.await(5L, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25L)
        assertTrue(message, predicate())
    }

    private fun assertNoFatal() {
        val output = logs()
        assertFalse(output, output.contains("FATAL EXCEPTION"))
        assertFalse(output, output.contains("ANR in ${context.packageName}"))
    }

    private fun logs() = shell("logcat -d -v brief -s PlayerAgent:V PlayerAgentLogBuffer:V AndroidRuntime:E ActivityManager:E")

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
