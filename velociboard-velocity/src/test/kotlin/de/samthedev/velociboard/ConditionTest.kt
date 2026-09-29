package de.samthedev.velociboard

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class ConditionTest {
    private val player = Mockito.mock(Player::class.java)
    private val placeholders = PlaceholderRegistry { }

    @Test
    fun comparesTextNumbersAndBooleans() {
        placeholders.register("server_name") { "lobby" }
        placeholders.register("queue_position") { "3" }
        placeholders.register("player_muted") { "true" }
        val values = HashMap<String, Component>()
        assertTrue(Condition.parse("%server_name% == lobby", "test").matches(player, placeholders, values))
        assertTrue(Condition.parse("%queue_position% > 0", "test").matches(player, placeholders, values))
        assertTrue(Condition.parse("%player_muted% == true", "test").matches(player, placeholders, values))
        assertFalse(Condition.parse("%queue_position% < 1", "test").matches(player, placeholders, values))
        assertFalse(Condition.parse("%unknown% == value", "test").matches(player, placeholders, values))
    }

    @Test
    fun rejectsInvalidExpressions() {
        assertThrows(IllegalArgumentException::class.java) { Condition.parse("%ping% > fast", "test") }
        assertThrows(IllegalArgumentException::class.java) { Condition.parse("ping == 5", "test") }
    }

    @Test
    fun checksLinePermission() {
        Mockito.`when`(player.hasPermission("velociboard.staff")).thenReturn(false, true)
        val line = BoardLine("Staff", null, "velociboard.staff")
        assertFalse(line.visible(player, placeholders, HashMap()))
        assertTrue(line.visible(player, placeholders, HashMap()))
    }
}
