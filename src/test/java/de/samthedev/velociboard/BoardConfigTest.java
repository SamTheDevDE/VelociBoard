package de.samthedev.velociboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void createsDefaultBoards() throws Exception {
        BoardConfig config = BoardConfig.load(directory);
        assertTrue(config.enabled());
        assertEquals("default", config.select("survival").id());
        assertEquals("lobby", config.select("lobby").id());
        assertTrue(Files.exists(directory.resolve("scoreboards/default.yml")));
    }

    @Test
    void migratesSingleBoardConfig() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                enabled: true
                title: "<gold>Old board"
                lines:
                  - "<gray>%player_name%"
                """);
        BoardConfig config = BoardConfig.load(directory);
        assertEquals("<gold>Old board", config.select("survival").title());
    }

    @Test
    void selectsHighestPriorityMatchingBoard() throws Exception {
        BoardConfig.load(directory);
        Path boards = directory.resolve("scoreboards");
        Files.writeString(boards.resolve("survival.yml"), """
                enabled: true
                servers: [survival]
                priority: 50
                title: Survival
                lines: [first]
                """);
        Files.writeString(boards.resolve("staff.yml"), """
                enabled: true
                servers: [survival]
                priority: 200
                title: Staff
                lines: [second]
                """);
        BoardConfig config = BoardConfig.load(directory);
        assertEquals("staff", config.select("survival").id());
        assertEquals("default", config.select("other").id());
    }

    @Test
    void validatesBoardFiles() throws Exception {
        BoardConfig.load(directory);
        Files.writeString(directory.resolve("scoreboards/lobby.yml"), """
                enabled: true
                servers: [lobby]
                priority: high
                title: Lobby
                lines: [line]
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BoardConfig.load(directory));
        assertTrue(error.getMessage().contains("scoreboards/lobby.yml: 'priority'"));
    }

    @Test
    void disabledGlobalConfigHasNoBoard() throws Exception {
        BoardConfig.load(directory);
        Files.writeString(directory.resolve("config.yml"), "enabled: false\n");
        BoardConfig config = BoardConfig.load(directory);
        assertFalse(config.enabled());
        assertNull(config.select("lobby"));
    }
}
