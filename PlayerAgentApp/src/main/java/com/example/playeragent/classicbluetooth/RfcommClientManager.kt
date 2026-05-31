package com.example.playeragent.classicbluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.example.playeragent.media.AlbumArtManager
import com.example.playeragent.media.MediaCommandExecutor
import com.example.playeragent.media.PlaybackStateAutoPushManager
import com.example.playeragent.media.PlaybackStateReader
import com.example.playeragent.media.VolumeDiagnosticManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class RfcommClientManager(
    context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val logger: (String) -> Unit
) {

    private val mediaCommandExecutor = MediaCommandExecutor(
        context = context.applicationContext,
        logger = logger,
        sendLine = ::sendMessage
    )
    private val volumeDiagnosticManager = VolumeDiagnosticManager(
        context = context.applicationContext,
        logger = logger
    )
    private val playbackStateReader = PlaybackStateReader(
        context = context.applicationContext,
        logger = logger
    )
    private val albumArtManager = AlbumArtManager(
        context = context.applicationContext,
        logger = logger,
        sendLine = ::sendMessage
    )
    private val playbackStateAutoPushManager = PlaybackStateAutoPushManager(
        playbackStateReader = playbackStateReader,
        sendLine = ::sendPlaybackStateMessage,
        logger = logger
    )
    private var socket: BluetoothSocket? = null
    private var connectThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun connectToServer() {
        close()

        connectThread = Thread {
            try {
                val device = findPairedControllerDevice()
                if (device == null) {
                    logger("RFCOMM server device not found in paired devices")
                    return@Thread
                }

                logger("RFCOMM connecting to ${device.name ?: "Unknown"} ${device.address}")
                bluetoothAdapter.cancelDiscovery()

                val bluetoothSocket = device.createRfcommSocketToServiceRecord(
                    RfcommConstants.SERVICE_UUID
                )
                socket = bluetoothSocket
                bluetoothSocket.connect()
                logger("Socket connected")
                playbackStateAutoPushManager.start()
                mediaCommandExecutor.sendVolumeState()

                readLoop(bluetoothSocket)
            } catch (securityException: SecurityException) {
                logger("RFCOMM connect failed: missing permission")
            } catch (exception: IOException) {
                logger("RFCOMM connect failed: ${exception.message}")
                close()
            }
        }.apply {
            name = "RfcommClientConnectThread"
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun findPairedControllerDevice(): BluetoothDevice? {
        val bondedDevices = bluetoothAdapter.bondedDevices ?: emptySet()

        for (device in bondedDevices) {
            val name = device.name ?: "Unknown"
            logger("Paired device: name=$name address=${device.address}")

            if (
                name.contains("MusicController", ignoreCase = true) ||
                name.contains("Xiaomi", ignoreCase = true) ||
                name.contains("小米")
            ) {
                return device
            }
        }

        return null
    }

    private fun readLoop(bluetoothSocket: BluetoothSocket) {
        try {
            val reader = BufferedReader(
                InputStreamReader(bluetoothSocket.inputStream, Charsets.UTF_8)
            )

            while (bluetoothSocket.isConnected) {
                val line = reader.readLine() ?: break
                logger("Received: $line")
                handleMessage(line)
            }
        } catch (exception: IOException) {
            logger("RFCOMM read stopped: ${exception.message}")
        } finally {
            close()
        }
    }

    private fun handleMessage(message: String) {
        try {
            val jsonObject = JSONObject(message)
            val command = jsonObject.optString("cmd")
            if (command.isBlank()) {
                logger("Command ignored: missing cmd")
                return
            }

            if (command == "GET_VOLUME") {
                mediaCommandExecutor.sendVolumeState()
                return
            }

            if (command == "DIAGNOSE_AUDIO_STREAMS") {
                val response = volumeDiagnosticManager.runAudioStreamDiagnostic()
                sendMessage(response.toString())
                return
            }

            if (command == "GET_PLAYBACK_STATE") {
                val response = playbackStateReader.readPlaybackState()
                sendPlaybackStateMessage(response.toString())
                return
            }

            if (command == "SEEK_TO") {
                mediaCommandExecutor.seekTo(jsonObject.optLong("position"))
                return
            }

            mediaCommandExecutor.execute(command)
        } catch (exception: Exception) {
            logger("Command parse failed: ${exception.message}")
        }
    }

    private fun sendPlaybackStateMessage(message: String) {
        sendMessage(message)

        try {
            val jsonObject = JSONObject(message)
            if (jsonObject.optString("type") == "playbackState") {
                albumArtManager.sendAlbumArtIfNeeded(jsonObject)
            }
        } catch (exception: Exception) {
            logger("Album art trigger failed: ${exception.message}")
        }
    }

    @Synchronized
    private fun sendMessage(message: String) {
        val connectedSocket = socket
        if (connectedSocket == null || !connectedSocket.isConnected) {
            logger("Cannot send response: RFCOMM not connected")
            return
        }

        try {
            connectedSocket.outputStream.write((message + "\n").toByteArray(Charsets.UTF_8))
            connectedSocket.outputStream.flush()
            logger("Sent: $message")
        } catch (exception: IOException) {
            logger("Send response failed: ${exception.message}")
        }
    }

    fun close() {
        playbackStateAutoPushManager.stop()

        try {
            socket?.close()
        } catch (_: IOException) {
        } finally {
            socket = null
        }

        connectThread = null
    }
}
