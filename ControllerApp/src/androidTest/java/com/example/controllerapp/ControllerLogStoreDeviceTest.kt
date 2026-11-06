package com.example.controllerapp

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controllerapp.data.ControllerLogStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ControllerLogStoreDeviceTest {
    @Test fun realWorkerClosesAndSnapshotSurvivesWritesAndClear() {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(base.cacheDir, "log-worker-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val log = ControllerLogStore(context)
        try {
            var listenerCalls = 0
            val notified = CountDownLatch(1)
            log.addListener { listenerCalls++; log.append("listener-reentry"); notified.countDown() }
            log.addListener { throw IllegalStateException("listener fault") }
            log.appendImportant("first")
            assertTrue(notified.await(5, TimeUnit.SECONDS))
            assertEquals(1, listenerCalls)
            val done = CountDownLatch(1)
            var exported: File? = null
            assertTrue(log.export { file, _ -> exported = file; done.countDown() })
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val snapshot = requireNotNull(exported)
            val original = snapshot.readText()
            assertTrue(original.contains("first"))
            assertTrue(original.contains("listener-reentry"))
            val producers = (0 until 4).map { producer -> thread {
                repeat(200) { log.append("producer-$producer-$it") }
            } }
            assertTrue(log.clear())
            producers.forEach { it.join() }
            log.close()
            log.close()
            assertTrue(log.awaitClosed(10_000))
            log.append("rejected-after-close")
            assertEquals(original, snapshot.readText())
        } finally {
            log.close()
            assertTrue(log.awaitClosed(10_000))
            root.deleteRecursively()
        }
    }
    @Test fun delayedDrainCompletesQueuedControlsAndStopsTheRealThread() {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(base.cacheDir, "log-delayed-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val log = ControllerLogStore(context)
        try {
            repeat(50) { log.append("before-$it") }
            val cleared = CountDownLatch(1)
            var clearSucceeded = false
            assertTrue(log.clear { clearSucceeded = it; cleared.countDown() })
            repeat(50) { log.append("after-$it") }
            val exported = CountDownLatch(1)
            var file: File? = null
            assertTrue(log.export { result, _ -> file = result; exported.countDown() })
            log.close()
            assertTrue(cleared.await(5, TimeUnit.SECONDS))
            assertTrue(clearSucceeded)
            assertTrue(exported.await(5, TimeUnit.SECONDS))
            assertTrue(log.awaitClosed(5_000))
            assertEquals(true, log.closeSucceeded())
            val contents = requireNotNull(file).readText()
            assertFalse(contents.contains("before-"))
            assertTrue(contents.contains("after-0"))
            assertTrue(contents.contains("after-49"))
        } finally {
            log.close()
            assertTrue(log.awaitClosed(5_000))
            root.deleteRecursively()
        }
    }

}
