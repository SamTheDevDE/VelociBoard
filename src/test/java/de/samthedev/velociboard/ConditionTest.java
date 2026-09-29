package de.samthedev.velociboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.Player;
import java.util.HashMap;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class ConditionTest {
    private final Player player = mock(Player.class);
    private final PlaceholderRegistry placeholders = new PlaceholderRegistry(ignored -> {});

    @Test
    void comparesTextNumbersAndBooleans() {
        placeholders.register("server_name", ignored -> "lobby");
        placeholders.register("queue_position", ignored -> "3");
        placeholders.register("player_muted", ignored -> "true");
        var values = new HashMap<String, Component>();

        assertTrue(Condition.parse("%server_name% == lobby", "test").matches(player, placeholders, values));
        assertTrue(Condition.parse("%queue_position% > 0", "test").matches(player, placeholders, values));
        assertTrue(Condition.parse("%player_muted% == true", "test").matches(player, placeholders, values));
        assertFalse(Condition.parse("%queue_position% < 1", "test").matches(player, placeholders, values));
        assertFalse(Condition.parse("%unknown% == value", "test").matches(player, placeholders, values));
    }

    @Test
    void rejectsInvalidExpressions() {
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("%ping% > fast", "test"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("ping == 5", "test"));
    }

    @Test
    void checksLinePermission() {
        when(player.hasPermission("velociboard.staff")).thenReturn(false, true);
        BoardLine line = new BoardLine("Staff", null, "velociboard.staff");
        assertFalse(line.visible(player, placeholders, new HashMap<>()));
        assertTrue(line.visible(player, placeholders, new HashMap<>()));
    }
}
