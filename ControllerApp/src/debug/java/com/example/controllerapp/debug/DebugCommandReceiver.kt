package com.example.controllerapp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.controllerapp.service.ControllerConnectionService

/**
 * A debug-build-only entry point used by ADB smoke and soak tests.
 *
 * The receiver is intentionally absent from release builds. It lets the test harness exercise
 * the same foreground-service command path used by MediaStyle notification actions without
 * requiring input injection privileges on heavily restricted Android vendor builds.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceAction = when (intent.getStringExtra(EXTRA_COMMAND)?.uppercase()) {
            "PREVIOUS" -> ControllerConnectionService.ACTION_PREVIOUS
            "PLAY_PAUSE" -> ControllerConnectionService.ACTION_PLAY_PAUSE
            "NEXT" -> ControllerConnectionService.ACTION_NEXT
            "RECONNECT" -> ControllerConnectionService.ACTION_RECONNECT
            else -> return
        }
        runCatching {
            // The smoke harness brings MainActivity to the foreground first, so the normal
            // connection service is already running. startService then only delivers the action.
            // Never try to create a connectedDevice FGS from this background-only test receiver:
            // Android 14+ correctly rejects that transition.
            context.startService(
                Intent(context, ControllerConnectionService::class.java).setAction(serviceAction)
            )
        }.onFailure {
            Log.w(TAG, "Debug command ignored because Controller service is not active", it)
        }
    }

    companion object {
        private const val TAG = "ControllerDebugCommand"
        const val EXTRA_COMMAND = "command"
    }
}
