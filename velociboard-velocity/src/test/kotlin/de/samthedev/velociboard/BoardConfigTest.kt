package de.samthedev.velociboard

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class BoardConfigTest {
    @TempDir lateinit var directory: Path

    @Test
    fun createsDefaultBoards() {
        val config = BoardConfig.load(directory)
        assertTrue(config.enabled)
        assertEquals("survival", config.select("survival")?.id)
        assertEquals("lobby", config.select("lobby")?.id)
        assertTrue(Files.exists(directory.resolve("scoreboards/default.yml")))
    }

    @Test
    fun bundledTorusBoardsSelectAndRenderWithoutOptionalIntegrations() {
        val config = BoardConfig.load(directory)
        val expected = mapOf(
            "lobby" to "lobby", "survival" to "survival", "hardcore" to "hardcore",
            "builder" to "builder", "limbo" to "queue", "queue" to "queue",
            "temporary" to "default",
        )
        assertEquals(expected.values.toSet(), config.boards.map { it.id }.toSet())

        val player = Mockito.mock(Player::class.java)
        val placeholders = PlaceholderRegistry { }
        placeholders.register("player_name") { "Alex" }
        placeholders.register("server_name") { "temporary" }
        placeholders.register("network_online") { "12" }
        placeholders.register("server_online") { "3" }
        placeholders.register("ping") { "42" }
        placeholders.register("luckperms_prefix") { "" }
        placeholders.setBackendResolver { _, _ -> "" }
        val plain = PlainTextComponentSerializer.plainText()

        for ((server, boardId) in expected) {
            val board = config.select(server)!!
            assertEquals(boardId, board.id)
            assertEquals("TORUSMC", plain.serialize(placeholders.render(player, board.title)))
            val resolved = HashMap<String, Component>()
            val lines = board.lines.filter { it.visible(player, placeholders, resolved) }
                .map { plain.serialize(placeholders.render(player, it.text, resolved)) }
            assertTrue(lines.size <= 14, "$server has too many visible lines")
            assertEquals(lines.filter { it.isNotEmpty() }.size, lines.filter { it.isNotEmpty() }.toSet().size)
            assertTrue(lines.all { it.length <= 40 && '<' !in it && '%' !in it }, "$server has unrendered or long text: $lines")
            assertTrue(lines.last().contains("play.torusmc.com"))
            if (server == "builder") assertTrue(lines.none { it.startsWith("World ") })
            if (server != "limbo" && server != "queue") assertTrue(lines.contains("Rank Member"))
        }

        placeholders.setBackendResolver { _, name -> if (name == "world") "build_world" else "" }
        val builder = config.select("builder")!!
        val resolved = HashMap<String, Component>()
        val builderLines = builder.lines.filter { it.visible(player, placeholders, resolved) }
            .map { plain.serialize(placeholders.render(player, it.text, resolved)) }
        assertTrue(builderLines.contains("World build_world"))

        placeholders.unregister("luckperms_prefix")
        placeholders.register("luckperms_prefix") { "Owner" }
        val ranked = config.select("lobby")!!
        val rankedResolved = HashMap<String, Component>()
        val rankedLines = ranked.lines.filter { it.visible(player, placeholders, rankedResolved) }
            .map { plain.serialize(placeholders.render(player, it.text, rankedResolved)) }
        assertTrue(rankedLines.contains("Rank Owner"))
        assertTrue(rankedLines.none { it == "Rank Member" })
    }

    @Test
    fun reloadsEditedBoardWithoutOverwritingIt() {
        BoardConfig.load(directory)
        val file = directory.resolve("scoreboards/queue.yml")
        val edited = Files.readString(file).replace("Please wait...", "Joining soon...")
        Files.writeString(file, edited)

        val reloaded = BoardConfig.load(directory)
        assertEquals(edited, Files.readString(file))
        assertTrue(reloaded.select("limbo")!!.lines.any { it.text.contains("Joining soon...") })
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
