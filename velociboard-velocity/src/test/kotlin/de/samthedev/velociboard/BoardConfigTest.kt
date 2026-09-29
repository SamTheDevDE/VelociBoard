package de.samthedev.velociboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class BoardConfigTest {
    @TempDir lateinit var directory: Path

    @Test
    fun createsDefaultBoards() {
        val config = BoardConfig.load(directory)
        assertTrue(config.enabled)
        assertEquals("default", config.select("survival")?.id)
        assertEquals("lobby", config.select("lobby")?.id)
        assertTrue(Files.exists(directory.resolve("scoreboards/default.yml")))
    }

    @Test
    fun migratesSingleBoardConfig() {
        Files.writeString(directory.resolve("config.yml"), """
            enabled: true
            title: "<gold>Old board"
            lines:
              - "<gray>%player_name%"
        """.trimIndent())
        val config = BoardConfig.load(directory)
        assertEquals("<gold>Old board", config.select("survival")?.title)
    }

    @Test
    fun selectsHighestPriorityMatchingBoard() {
        BoardConfig.load(directory)
        val boards = directory.resolve("scoreboards")
        Files.writeString(boards.resolve("survival.yml"), """
            enabled: true
            servers: [survival]
            priority: 50
            title: Survival
            lines: [first]
        """.trimIndent())
        Files.writeString(boards.resolve("staff.yml"), """
            enabled: true
            servers: [survival]
            priority: 200
            title: Staff
            lines: [second]
        """.trimIndent())
        val config = BoardConfig.load(directory)
        assertEquals("staff", config.select("survival")?.id)
        assertEquals("default", config.select("other")?.id)
    }

    @Test
    fun validatesBoardFiles() {
        BoardConfig.load(directory)
        Files.writeString(directory.resolve("scoreboards/lobby.yml"), """
            enabled: true
            servers: [lobby]
            priority: high
            title: Lobby
            lines: [line]
        """.trimIndent())
        val error = assertThrows(IllegalArgumentException::class.java) { BoardConfig.load(directory) }
        assertTrue(error.message!!.contains("scoreboards/lobby.yml: 'priority'"))
    }

    @Test
    fun disabledGlobalConfigHasNoBoard() {
        BoardConfig.load(directory)
        Files.writeString(directory.resolve("config.yml"), "enabled: false\n")
        val config = BoardConfig.load(directory)
        assertFalse(config.enabled)
        assertNull(config.select("lobby"))
    }

    @Test
    fun readsRefreshIntervals() {
        BoardConfig.load(directory)
        Files.writeString(directory.resolve("config.yml"), """
            enabled: true
            placeholder-refresh:
              ping: 2500
        """.trimIndent())
        assertEquals(Duration.ofMillis(2500), BoardConfig.load(directory).placeholderRefresh["ping"])
        Files.writeString(directory.resolve("config.yml"), """
            enabled: true
            placeholder-refresh:
              ping: -1
        """.trimIndent())
        assertThrows(IllegalArgumentException::class.java) { BoardConfig.load(directory) }
    }

    @Test
    fun readsConditionalLines() {
        BoardConfig.load(directory)
        Files.writeString(directory.resolve("scoreboards/lobby.yml"), """
            enabled: true
            servers: [lobby]
            priority: 100
            condition: "%server_name% == lobby"
            title: Lobby
            lines:
              - "Everyone"
              - text: "Staff"
                permission: velociboard.staff
        """.trimIndent())
        val config = BoardConfig.load(directory)
        assertTrue(config.hasConditions())
        assertEquals(2, config.select("lobby")?.lines?.size)
        assertEquals("velociboard.staff", config.select("lobby")?.lines?.get(1)?.permission)
    }
}
