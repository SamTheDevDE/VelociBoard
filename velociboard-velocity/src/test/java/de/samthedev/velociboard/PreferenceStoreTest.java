package de.samthedev.velociboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

class PreferenceStoreTest {
    @TempDir
    Path directory;

    @Test
    void savesAndLoadsAcrossStoreRestarts() {
        Path file = directory.resolve("preferences.db");
        UUID playerId = UUID.randomUUID();
        PreferenceStore first = new PreferenceStore(file, mock(Logger.class));
        first.start();
        assertFalse(first.load(playerId).join());
        first.save(playerId, true).join();
        first.close().join();

        PreferenceStore second = new PreferenceStore(file, mock(Logger.class));
        second.start();
        assertTrue(second.load(playerId).join());
        second.save(playerId, false).join();
        assertFalse(second.load(playerId).join());
        second.close().join();
    }
}
