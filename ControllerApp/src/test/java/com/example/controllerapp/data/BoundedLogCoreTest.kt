package com.example.controllerapp.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BoundedLogCoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val tasks = ArrayDeque<() -> Unit>()
    private var stopped = false
    private lateinit var directory: File
    private lateinit var snapshots: File

    private fun core(io: LogFileOperations = LogFileOperations(), limit: Int = 128, pending: Int = 256): BoundedLogCore {
        directory = temporary.newFolder()
        snapshots = temporary.newFolder()
        return BoundedLogCore(directory, snapshots, { tasks.addLast(it); true }, { stopped = true }, io, limit, pending)
    }
    private fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().invoke() }
    private fun export(core: BoundedLogCore): File {
        var file: File? = null
        var error: String? = null
        assertTrue(core.export { result, failure -> file = result; error = failure })
        drain()
        assertNull(error)
        return requireNotNull(file)
    }

    @Test fun rotatesBoundsLegacyAndExportsImmutableOrderedSnapshot() {
        val log = core(limit = 40)
        File(directory, "controller-current.log").writeText("old-".repeat(15) + "\nrecent-old-line\n")
        log.append("first", false)
        drain()
        assertTrue(File(directory, "controller-current.log").readText().contains("recent-old-line"))
        assertFalse(File(directory, "controller-current.log").readText().contains("old-old-old-old"))
        for (n in 0..8) { log.append("item-$n-123456", false); drain() }
        val snapshot = export(log)
        val contents = snapshot.readText()
        assertTrue(contents.contains("item-8"))
        assertTrue(contents.indexOf("item-7") < contents.indexOf("item-8"))
        assertTrue(directory.listFiles()!!.size <= 4)
        assertTrue(directory.listFiles()!!.all { it.length() <= 40 })
        log.append("later", true)
        drain()
        assertEquals(contents, snapshot.readText())
        assertTrue(snapshot.length() <= 160)
    }

    @Test fun boundedBurstPreservesImportantAndSummarizesDrops() {
        val log = core(limit = 128, pending = 40)
        repeat(20) { log.append("normal-$it", false) }
        assertTrue(log.append("CRITICAL", true))
        assertEquals(1, tasks.size)
        drain()
        val contents = export(log).readText()
        assertTrue(contents.contains("CRITICAL"))
        assertTrue(contents.contains("dropped"))
        assertFalse(log.append("x".repeat(100_000), false))
    }

    @Test fun clearExportAndCloseAreOrderedAndCloseDoesNotNeedControlSlot() {
        val log = core()
        log.append("before", false)
        assertTrue(log.clear())
        log.append("after", false)
        var exported: File? = null
        assertTrue(log.export { file, _ -> exported = file })
        repeat(14) { assertTrue(log.clear()) }
        assertFalse(log.clear())
        log.close()
        log.close()
        assertFalse(log.append("after-close", true))
        drain()
        assertTrue(log.awaitClosed(100))
        assertTrue(stopped)
        assertEquals("after\n", requireNotNull(exported).readText())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun directoryAndEachFileFailureAreIsolatedAndLaterWritesRecover() {
        for (kind in listOf("directory", "write", "flush", "close", "rename", "delete")) {
            val faults = Faults()
            val log = core(faults, limit = 32)
            log.append("seed", false); drain()
            if (kind == "rename" || kind == "delete") {
                repeat(4) { log.append("1234567890123456789012345", false); drain() }
            }
            faults.failure = kind
            log.append("1234567890123456789012345", false); drain()
            var error: String? = null
            log.export { _, e -> error = e }; drain()
            assertNotNull("$kind failure must be visible", error)
            faults.failure = null
            log.append("recovered", true); drain()
            assertTrue("$kind did not recover", export(log).readText().contains("recovered"))
            log.close(); drain(); assertTrue(log.awaitClosed(100))
        }
    }

    @Test fun refusedSchedulerClosesWithoutLeakingOrAcceptingCommands() {
        val log = BoundedLogCore(temporary.newFolder(), temporary.newFolder(), { false }, { stopped = true })
        assertFalse(log.append("test", false))
        assertTrue(log.awaitClosed(100))
        assertTrue(stopped)
        assertFalse(log.export { _, _ -> fail("not accepted") })
    }

    @Test fun queuedBurstUsesOneWritePerBatchAndControlCommandsRemainOrdered() {
        var writes = 0
        val io = object : LogFileOperations() {
            override fun openAppend(file: File): OutputStream {
                writes++
                return super.openAppend(file)
            }
        }
        val log = core(io, limit = 4096, pending = 4096)
        repeat(50) { assertTrue(log.append("before-$it", false)) }
        var clearSucceeded = false
        assertTrue(log.clear { clearSucceeded = it })
        repeat(50) { assertTrue(log.append("after-$it", false)) }
        var exported: File? = null
        assertTrue(log.export { file, _ -> exported = file })
        log.close()
        assertEquals("one scheduled drain for the entire queued burst", 1, tasks.size)
        drain()
        assertTrue(clearSucceeded)
        assertTrue(log.awaitClosed(100))
        assertEquals(true, log.closeSucceeded())
        assertEquals("clear separates the burst into two disk writes", 2, writes)
        val snapshot = requireNotNull(exported).readText()
        assertFalse(snapshot.contains("before-"))
        assertEquals((0 until 50).map { "after-$it" }, snapshot.lines().filter { it.isNotEmpty() })
    }

    @Test fun listenerCallbacksAndClearFollowTheSameSerialOrder() {
        val events = mutableListOf<String>()
        val log = BoundedLogCore(temporary.newFolder(), temporary.newFolder(),
            { tasks.addLast(it); true }, {}, onLine = { events.add(it) })
        log.append("before", false)
        assertTrue(log.clear { events.add("clear:$it") })
        log.append("after", false)
        drain()
        assertEquals(listOf("before", "clear:true", "after"), events)
    }

    @Test fun closeExposesUnrecoveredWriteFailure() {
        val faults = Faults().apply { failure = "flush" }
        val log = core(faults)
        assertTrue(log.append("last", true))
        log.close(); log.close(); drain()
        assertTrue(log.awaitClosed(100))
        assertEquals(false, log.closeSucceeded())
    }

    @Test fun snapshotLimitCanBeReleasedOnlyByExplicitCleanup() {
        val log = core()
        log.append("one", false); drain()
        val snapshots = (1..4).map { export(log) }
        var failure: String? = null
        assertTrue(log.export { _, error -> failure = error }); drain()
        assertNotNull(failure)
        assertTrue(snapshots.all { it.exists() })
        var cleared = false
        assertTrue(log.clearSnapshots { cleared = it }); drain()
        assertTrue(cleared)
        assertTrue(snapshots.none { it.exists() })
        assertTrue(export(log).readText().contains("one"))
    }

    @Test fun blockedWriterKeepsQueueBoundedAndConcurrentCloseCompletes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val faults = object : LogFileOperations() {
            override fun append(file: File, bytes: ByteArray) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                super.append(file, bytes)
            }
        }
        val log = BoundedLogCore(temporary.newFolder(), temporary.newFolder(),
            { task -> executor.execute(task); true }, { executor.shutdown() }, faults, 256, 128)
        try {
            assertTrue(log.append("first", false))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            var accepted = 0
            repeat(10000) { if (log.append("0123456789", false)) accepted++ }
            assertTrue("queue exceeded its byte budget", accepted <= 12)
            assertTrue(log.append("important", true))
            val exported = CountDownLatch(1)
            assertTrue(log.export { _, _ -> exported.countDown() })
            assertTrue(log.clear())
            log.close()
            assertFalse(log.append("late", true))
            release.countDown()
            assertTrue(exported.await(5, TimeUnit.SECONDS))
            assertTrue(log.awaitClosed(5_000))
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private class Faults : LogFileOperations() {
        var failure: String? = null
        override fun ensureDirectory(dir: File) {
            if (failure == "directory") throw IOException("directory")
            super.ensureDirectory(dir)
        }
        override fun deleteIfExists(file: File) {
            if (failure == "delete") throw IOException("delete")
            super.deleteIfExists(file)
        }
        override fun moveIfExists(from: File, to: File) {
            if (failure == "rename") throw IOException("rename")
            super.moveIfExists(from, to)
        }
        override fun openAppend(file: File): OutputStream {
            return object : FilterOutputStream(super.openAppend(file)) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (failure == "write") throw IOException("write")
                    out.write(b, off, len)
                }
                override fun flush() {
                    if (failure == "flush") throw IOException("flush")
                    super.flush()
                }
                override fun close() {
                    if (failure == "close") { out.close(); throw IOException("close") }
                    super.close()
                }
            }
        }
    }
}
