package de.samthedev.velociboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardConfigTest {
    @TempDir
    Path directory;

    @Test
    void createsDefaultConfig() throws Exception {
        assertTrue(BoardConfig.load(directory).enabled());
        assertTrue(Files.exists(directory.resolve("config.yml")));
    }

    @Test
    void readsAndValidatesEnabled() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "enabled: false\n");
        assertFalse(BoardConfig.load(directory).enabled());

        Files.writeString(file, "enabled: nope\n");
        assertThrows(IllegalArgumentException.class, () -> BoardConfig.load(directory));
    }
}
