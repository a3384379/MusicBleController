package com.example.playeragent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.playeragent.service.PlayerAgentForegroundService

class PlayerAgentDebugControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "[DebugControl] received action=$action")

        when (action) {
            ACTION_START_BLE_SERVICE -> {
                forwardToService(
                    context = context,
                    serviceAction = null,
                    logAction = "START_BLE_SERVICE"
                )
            }
            ACTION_STOP_BLE_SERVICE -> {
                forwardToService(
                    context = context,
                    serviceAction = PlayerAgentForegroundService.ACTION_STOP_SERVICE,
                    logAction = "STOP_BLE_SERVICE"
                )
            }
            ACTION_RECOVER_BLE_STACK -> {
                forwardToService(
                    context = context,
                    serviceAction = PlayerAgentForegroundService.ACTION_RECOVER_BLE_STACK,
                    logAction = "RECOVER_BLE_STACK"
                )
            }
            ACTION_DUMP_BLE_DIAGNOSTICS -> {
                forwardToService(
                    context = context,
                    serviceAction = PlayerAgentForegroundService.ACTION_DUMP_BLE_DIAGNOSTICS,
                    logAction = "DUMP_BLE_DIAGNOSTICS"
                )
            }
            else -> Log.w(TAG, "[DebugControl] ignored action=$action")
        }
    }

    private fun forwardToService(
        context: Context,
        serviceAction: String?,
        logAction: String
    ) {
        val serviceIntent = Intent(context, PlayerAgentForegroundService::class.java).apply {
            action = serviceAction
        }
        Log.i(
            TAG,
            "[DebugControl] forwarded to service action=${serviceAction ?: "DEFAULT_START"}"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        Log.i(TAG, "[DebugControl] completed action=$logAction")
    }

    companion object {
        const val ACTION_START_BLE_SERVICE =
            "com.example.playeragent.debug.START_BLE_SERVICE"
        const val ACTION_STOP_BLE_SERVICE =
            "com.example.playeragent.debug.STOP_BLE_SERVICE"
        const val ACTION_RECOVER_BLE_STACK =
            "com.example.playeragent.debug.RECOVER_BLE_STACK"
        const val ACTION_DUMP_BLE_DIAGNOSTICS =
            "com.example.playeragent.debug.DUMP_BLE_DIAGNOSTICS"
        private const val TAG = "PlayerAgent"
    }
}
