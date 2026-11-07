package com.example.controllerapp.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** All disk operations run on the scheduler's single worker, in queue order. */
internal class BoundedLogCore(
    private val directory: File,
    private val snapshots: File,
    private val schedule: (() -> Unit) -> Boolean,
    private val stop: () -> Unit,
    private val io: LogFileOperations = LogFileOperations(),
    private val fileLimit: Int = 4 * 1024 * 1024,
    private val pendingLimit: Int = 512 * 1024,
    private val maxFiles: Int = 4,
    private val onLine: (String) -> Unit = {}
) {
    private sealed class Item {
        class Line(val bytes: ByteArray, val important: Boolean, val notify: Boolean) : Item()
        class Clear(val done: (Boolean) -> Unit) : Item()
        class Export(val done: (File?, String?) -> Unit) : Item()
        class ClearSnapshots(val done: (Boolean) -> Unit) : Item()
        object Close : Item()
    }
    private val lock = Any()
    private val queue = ArrayDeque<Item>()
    private val closed = CountDownLatch(1)
    private var pendingBytes = 0
    private var commands = 0
    private var scheduled = false
    private var closing = false
    private var dropped = 0L
    private var initialized = false
    private var failedWrites = 0L
    @Volatile private var closeResult: Boolean? = null

    fun append(line: String, important: Boolean, notify: Boolean = true): Boolean {
        // Bound the transient UTF-8 allocation even when a caller supplies a huge string.
        val text = if (line.length > 8192) line.take(8192) + " [truncated]" else line
        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            if (closing) return false
            if (important) {
                val it = queue.iterator()
                while ((pendingBytes + bytes.size > pendingLimit || queue.size >= 2048) && it.hasNext()) {
                    val item = it.next()
                    if (item is Item.Line && !item.important) {
                        it.remove()
                        pendingBytes -= item.bytes.size
                        dropped++
                    }
                }
            }
            if (bytes.size > fileLimit || bytes.size > pendingLimit ||
                pendingBytes + bytes.size > pendingLimit || queue.size >= 2048) {
                dropped++
                return false
            }
            queue.addLast(Item.Line(bytes, important, notify))
            pendingBytes += bytes.size
            return scheduleLocked()
        }
    }

    fun clear(done: (Boolean) -> Unit = {}): Boolean = command(Item.Clear(done))
    fun export(done: (File?, String?) -> Unit): Boolean = command(Item.Export(done))
    fun clearSnapshots(done: (Boolean) -> Unit): Boolean = command(Item.ClearSnapshots(done))

    private fun command(item: Item): Boolean = synchronized(lock) {
        if (closing || commands >= 16) return false
        commands++
        queue.addLast(item)
        scheduleLocked()
    }

    fun close() {
        synchronized(lock) {
            if (closing) return
            closing = true
            // Close is reserved independently of the bounded control queue.
            queue.addLast(Item.Close)
            if (!scheduleLocked()) closed.countDown()
        }
    }

    fun awaitClosed(timeoutMs: Long): Boolean = closed.await(timeoutMs, TimeUnit.MILLISECONDS)
    fun closeSucceeded(): Boolean? = closeResult

    private fun scheduleLocked(): Boolean {
        if (scheduled) return true
        scheduled = true
        if (try { schedule(::drain) } catch (_: Exception) { false }) return true
        scheduled = false
        closing = true
        queue.clear()
        pendingBytes = 0
        commands = 0
        closeResult = false
        try { stop() } catch (_: Exception) { } finally { closed.countDown() }
        return false
    }

    private fun drain() {
        while (true) {
            val item = synchronized(lock) {
                if (queue.isEmpty()) {
                    scheduled = false
                    return
                }
                queue.removeFirst().also {
                    when (it) {
                        is Item.Line -> Unit // Includes this in-flight batch until its write completes.
                        is Item.Clear, is Item.Export, is Item.ClearSnapshots -> commands--
                        Item.Close -> Unit
                    }
                }
            }
            when (item) {
                is Item.Line -> {
                    // Gather adjacent entries only; controls remain strict ordering barriers.
                    val lines = ArrayList<Item.Line>()
                    lines.add(item)
                    var bytes = item.bytes.size
                    synchronized(lock) {
                        while (queue.peekFirst() is Item.Line) {
                            val next = queue.peekFirst() as Item.Line
                            if (bytes + next.bytes.size > minOf(fileLimit, 64 * 1024)) break
                            lines.add(queue.removeFirst() as Item.Line)
                            bytes += next.bytes.size
                        }
                    }
                    val lost = synchronized(lock) { dropped.also { dropped = 0 } }
                    if (failedWrites > 0) {
                        val marker = "[LogStore] lost $failedWrites writes\n".toByteArray(Charsets.UTF_8)
                        if (writeSafely(marker)) failedWrites = 0
                    }
                    if (lost > 0 && !writeSafely("[LogStore] dropped $lost queued messages\n".toByteArray(Charsets.UTF_8))) {
                        failedWrites++
                    }
                    val batch = ByteArray(bytes)
                    var offset = 0
                    lines.forEach { line ->
                        System.arraycopy(line.bytes, 0, batch, offset, line.bytes.size)
                        offset += line.bytes.size
                    }
                    if (!writeSafely(batch)) failedWrites += lines.size
                    synchronized(lock) { pendingBytes -= bytes }
                    lines.filter { it.notify }.forEach { line ->
                        safeCallback { onLine(String(line.bytes, Charsets.UTF_8).removeSuffix("\n")) }
                    }
                }
                is Item.Clear -> {
                    val ok = runCatching {
                        prepare()
                        for (index in 0 until maxFiles) io.deleteIfExists(file(index))
                        failedWrites = 0
                    }.isSuccess
                    safeCallback { item.done(ok) }
                }
                is Item.Export -> {
                    val result = runCatching { snapshot() }
                    safeCallback { item.done(result.getOrNull(), result.exceptionOrNull()?.message) }
                }
                is Item.ClearSnapshots -> {
                    val ok = runCatching {
                        io.ensureDirectory(snapshots)
                        val files = snapshots.listFiles() ?: throw IOException("Cannot list log snapshots")
                        files.forEach(io::deleteIfExists)
                    }.isSuccess
                    safeCallback { item.done(ok) }
                }
                Item.Close -> {
                    val lost = synchronized(lock) { dropped.also { dropped = 0 } }
                    if (lost > 0 && !writeSafely("[LogStore] dropped $lost queued messages\n".toByteArray(Charsets.UTF_8))) {
                        failedWrites++
                    }
                    closeResult = failedWrites == 0L
                    try { stop() } catch (_: Exception) { closeResult = false } finally { closed.countDown() }
                    return
                }
            }
        }
    }

    private fun safeCallback(block: () -> Unit) { try { block() } catch (_: Exception) { } }

    private fun prepare() {
        io.ensureDirectory(directory)
        if (!initialized) {
            // Old versions used a single unlimited file. Bound it before any export or append.
            for (i in 0 until maxFiles) io.limit(file(i), fileLimit.toLong())
            initialized = true
        }
    }

    private fun writeSafely(bytes: ByteArray): Boolean {
        return try {
            if (bytes.size > fileLimit) throw IOException("Log line exceeds file limit")
            prepare()
            val current = file(0)
            if (io.length(current) + bytes.size > fileLimit) rotate()
            io.append(current, bytes)
            true
        } catch (_: Exception) {
            // Drop this write; retry only on a subsequent operation, never recursively.
            false
        }
    }

    private fun rotate() {
        io.deleteIfExists(file(maxFiles - 1))
        for (i in maxFiles - 2 downTo 0) io.moveIfExists(file(i), file(i + 1))
    }

    private fun snapshot(): File {
        prepare()
        if (failedWrites > 0) throw IOException("Recent log writes failed")
        io.ensureDirectory(snapshots)
        // Do not overwrite an issued URI: another app may still be reading it.
        val existing = snapshots.listFiles() ?: throw IOException("Cannot list log snapshots")
        if (existing.size >= 4) throw IOException("已达到导出副本上限，请删除旧副本后重试")
        val target = File.createTempFile("controller-", ".log", snapshots)
        try {
            io.snapshot((maxFiles - 1 downTo 0).map(::file), target, fileLimit.toLong() * maxFiles)
            return target
        } catch (e: Exception) {
            try { io.deleteIfExists(target) } catch (_: Exception) { }
            throw e
        }
    }

    private fun file(index: Int) = File(directory, if (index == 0) "controller-current.log" else "controller-$index.log")
}

/** Overridable for deterministic fault injection in JVM tests. */
internal open class LogFileOperations {
    open fun ensureDirectory(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create log directory")
    }
    open fun length(file: File): Long = file.length()
    open fun limit(file: File, max: Long) {
        if (file.exists() && file.length() > max) RandomAccessFile(file, "rw").use { input ->
            input.seek(input.length() - max)
            val tail = ByteArray(max.toInt())
            input.readFully(tail)
            val newline = tail.indexOf('\n'.code.toByte())
            val start = if (newline >= 0) newline + 1 else 0
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.IGNORE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.IGNORE)
            val valid = decoder.decode(java.nio.ByteBuffer.wrap(tail, start, tail.size - start))
                .toString().toByteArray(Charsets.UTF_8)
            input.seek(0)
            input.write(valid)
            input.setLength(valid.size.toLong())
        }
    }
    open fun deleteIfExists(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Cannot delete log file")
    }
    open fun moveIfExists(from: File, to: File) {
        if (from.exists() && !from.renameTo(to)) throw IOException("Cannot rotate log file")
    }
    open fun openAppend(file: File): OutputStream = FileOutputStream(file, true)
    open fun append(file: File, bytes: ByteArray) {
        openAppend(file).use { it.write(bytes); it.flush() }
    }
    open fun snapshot(files: List<File>, target: File, limit: Long) {
        var total = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(8192)
            for (file in files) if (file.exists()) {
                FileInputStream(file).use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > limit) throw IOException("Log snapshot exceeds limit")
                        output.write(buffer, 0, count)
                    }
                }
            }
            output.flush()
        }
    }
}
