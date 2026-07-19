package com.example.playeragent.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QrcParsedCacheIndexStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun atomicallyPersistsAndReloadsIncrementalUpdates() {
        val directory = temporaryFolder.newFolder("parsed-index")
        val store = QrcParsedCacheIndexStore(directory) { }
        assertFalse(store.loaded)
        store.upsert(entry("song-a", "a.json", 60))
        store.upsert(entry("song-b", "b.json", 42))
        store.upsert(entry("song-a", "a2.json", 61))
        store.flushNow()
        store.close()

        assertTrue(File(directory, QrcParsedCacheIndexStore.INDEX_FILE_NAME).isFile)
        val reloaded = QrcParsedCacheIndexStore(directory) { }
        assertTrue(reloaded.loaded)
        assertEquals(2, reloaded.snapshot().size)
        assertEquals("a2.json", reloaded.snapshot().first { it.songKey == "song-a" }.fileName)
        reloaded.close()
    }

    @Test
    fun damagedOrWrongVersionIndexRecoversOnReplace() {
        val directory = temporaryFolder.newFolder("damaged-index")
        val file = File(directory, QrcParsedCacheIndexStore.INDEX_FILE_NAME)
        file.writeText("{damaged", Charsets.UTF_8)
        val damaged = QrcParsedCacheIndexStore(directory) { }
        assertFalse(damaged.loaded)
        damaged.replace(listOf(entry("song-a", "a.json", 30)))
        damaged.flushNow()
        damaged.close()

        file.writeText(file.readText().replace("\"version\":1", "\"version\":999"))
        val wrongVersion = QrcParsedCacheIndexStore(directory) { }
        assertFalse(wrongVersion.loaded)
        wrongVersion.replace(listOf(entry("song-b", "b.json", 50)))
        wrongVersion.flushNow()
        wrongVersion.close()

        val recovered = QrcParsedCacheIndexStore(directory) { }
        assertTrue(recovered.loaded)
        assertEquals(listOf("song-b"), recovered.snapshot().map { it.songKey })
        recovered.close()
    }

    private fun entry(songKey: String, fileName: String, lines: Int) =
        QrcParsedCacheIndexStore.Entry(
            songKey = songKey,
            normalizedTitle = songKey,
            normalizedArtist = "artist",
            normalizedAlbum = "album",
            title = songKey,
            artist = "artist",
            album = "album",
            groupId = "123",
            fileName = fileName,
            lines = lines,
            createdAt = 123L,
            fingerprint = "1:2:3"
        )
}
