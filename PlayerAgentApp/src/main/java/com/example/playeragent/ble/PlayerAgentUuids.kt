package com.example.playeragent.ble

import java.util.UUID

object PlayerAgentUuids {
    val SERVICE_UUID: UUID = UUID.fromString("0000a001-0000-1000-8000-00805f9b34fb")
    val COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
    val STATUS_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000a003-0000-1000-8000-00805f9b34fb")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val ADVERTISED_NAME = "SonyPlayerAgent"
}
