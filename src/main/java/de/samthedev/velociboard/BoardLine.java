package de.samthedev.velociboard;

import com.velocitypowered.api.proxy.Player;
import java.util.Map;
import net.kyori.adventure.text.Component;

record BoardLine(String text, Condition condition, String permission) {
    static BoardLine parse(Object value, String location) {
        if (value instanceof String text) {
            return new BoardLine(text, null, null);
        }
        if (!(value instanceof Map<?, ?> settings)) {
            throw new IllegalArgumentException(location + ": must be text or a mapping");
        }
        Object text = settings.get("text");
        if (!(text instanceof String content)) {
            throw new IllegalArgumentException(location + ".text: must be text");
        }
        return new BoardLine(content, BoardDefinition.parseCondition(settings, location),
                BoardDefinition.parsePermission(settings, location));
    }

    boolean visible(Player player, PlaceholderRegistry placeholders, Map<String, Component> resolved) {
        return (permission == null || player.hasPermission(permission))
                && (condition == null || condition.matches(player, placeholders, resolved));
    }
}
