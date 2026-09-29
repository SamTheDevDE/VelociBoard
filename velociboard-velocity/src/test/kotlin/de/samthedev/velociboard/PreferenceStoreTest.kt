package de.samthedev.velociboard

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.slf4j.Logger
import java.nio.file.Path
import java.util.UUID

class PreferenceStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun savesAndLoadsAcrossStoreRestarts() {
        val file = directory.resolve("preferences.db")
        val playerId = UUID.randomUUID()
        val first = PreferenceStore(file, Mockito.mock(Logger::class.java))
        first.start()
        assertFalse(first.load(playerId).join())
        first.save(playerId, true).join()
        first.close().join()

        val second = PreferenceStore(file, Mockito.mock(Logger::class.java))
        second.start()
        assertTrue(second.load(playerId).join())
        second.save(playerId, false).join()
        assertFalse(second.load(playerId).join())
        second.close().join()
    }
}
