package com.example.controllerapp.classicbluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class RfcommServerManager(
    private val bluetoothAdapter: BluetoothAdapter,
    private val logger: (String) -> Unit,
    private val onMessageReceived: (String) -> Unit = {}
) {

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var acceptThread: Thread? = null
    private var readThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun startServer() {
        stopServer()

        acceptThread = Thread {
            try {
                logger("RFCOMM server starting")
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                    RfcommConstants.SERVICE_NAME,
                    RfcommConstants.SERVICE_UUID
                )
                logger("RFCOMM server listening")

                val socket = serverSocket?.accept()
                clientSocket = socket

                if (socket != null) {
                    logger("Client connected")
                    startReadLoop(socket)
                }
            } catch (exception: IOException) {
                logger("RFCOMM server failed: ${exception.message}")
            } catch (securityException: SecurityException) {
                logger("RFCOMM server failed: missing permission")
            }
        }.apply {
            name = "RfcommServerAcceptThread"
            start()
        }
    }

    fun sendPing() {
        sendCommand("PING")
    }

    fun sendGetVolume() {
        sendRawMessage("""{"cmd":"GET_VOLUME"}""")
    }

    fun sendAudioStreamDiagnostic() {
        sendRawMessage("""{"cmd":"DIAGNOSE_AUDIO_STREAMS"}""")
    }

    fun sendPlaybackStateRequest() {
        sendRawMessage("""{"cmd":"GET_PLAYBACK_STATE"}""")
    }

    fun sendSeekTo(positionMs: Long) {
        sendRawMessage("""{"cmd":"SEEK_TO","position":$positionMs}""")
    }

    fun sendCommand(command: String) {
        val message = """{"cmd":"$command","time":${System.currentTimeMillis()}}"""
        sendRawMessage(message)
    }

    private fun sendRawMessage(message: String) {
        val socket = clientSocket
        if (socket == null || !socket.isConnected) {
            logger("RFCOMM not connected")
            return
        }

        logger("Sending command: $message")

        Thread {
            try {
                socket.outputStream.write((message + "\n").toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                logger("Command sent")
            } catch (exception: IOException) {
                logger("Send command failed: ${exception.message}")
            }
        }.apply {
            name = "RfcommServerCommandThread"
            start()
        }
    }

    private fun startReadLoop(socket: BluetoothSocket) {
        readThread = Thread {
            try {
                val reader = BufferedReader(
                    InputStreamReader(socket.inputStream, Charsets.UTF_8)
                )

                while (socket.isConnected) {
                    val line = reader.readLine() ?: break
                    logger("Received: $line")
                    onMessageReceived(line)
                }
            } catch (exception: IOException) {
                logger("RFCOMM read stopped: ${exception.message}")
            }
        }.apply {
            name = "RfcommServerReadThread"
            start()
        }
    }

    fun stopServer() {
        try {
            clientSocket?.close()
        } catch (_: IOException) {
        } finally {
            clientSocket = null
        }

        try {
            serverSocket?.close()
        } catch (_: IOException) {
        } finally {
            serverSocket = null
        }

        acceptThread = null
        readThread = null
    }
}
