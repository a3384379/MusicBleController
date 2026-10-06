// SPDX-License-Identifier: GPL-3.0-only
// JNI ABI from jqssun/android-airplay-server v0.0.31, c8defdd70d7e6a04f4f1b71d353653682d594106.
package io.github.jqssun.airplay.bridge

/**
 * log lines from native code, forwarded to UI; called directly from native threads
 * (attached to JVM for the call), implementations must be thread-safe and cheap
 */
interface LogListener {
    fun onLog(msg: String)
}
