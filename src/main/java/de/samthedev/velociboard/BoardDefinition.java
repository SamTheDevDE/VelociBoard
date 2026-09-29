package de.samthedev.velociboard;

import com.velocitypowered.api.proxy.Player;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;

record BoardDefinition(String id, boolean enabled, List<String> servers, int priority, String title,
        List<BoardLine> lines, Condition condition, String permission) {
    static BoardDefinition load(Path file, Map<?, ?> values) {
        String name = file.getFileName().toString();
        String location = "scoreboards/" + name;
        String id = name.substring(0, name.length() - 4);
        Object enabled = values.containsKey("enabled") ? values.get("enabled") : Boolean.TRUE;
        if (!(enabled instanceof Boolean active)) {
            throw invalid(location, "enabled", "must be true or false");
        }
        Object servers = values.containsKey("servers") ? values.get("servers") : List.of();
        if (!(servers instanceof List<?> serverNames)
                || serverNames.stream().anyMatch(server -> !(server instanceof String))) {
            throw invalid(location, "servers", "must be a list of server names");
        }
        Object priority = values.containsKey("priority") ? values.get("priority") : 0;
        if (!(priority instanceof Integer order)) {
            throw invalid(location, "priority", "must be a number");
        }
        Object title = values.get("title");
        if (!(title instanceof String titleText)) {
            throw invalid(location, "title", "must be text");
        }
        Object lines = values.get("lines");
        if (!(lines instanceof List<?> entries) || entries.size() > 15) {
            throw invalid(location, "lines", "must be a list of up to 15 entries");
        }
        List<BoardLine> parsedLines = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            parsedLines.add(BoardLine.parse(entries.get(index), location + ": 'lines[" + index + "]'"));
        }
        return new BoardDefinition(id, active, serverNames.stream().map(String.class::cast).toList(), order,
                titleText, List.copyOf(parsedLines), parseCondition(values, location), parsePermission(values, location));
    }

    boolean matches(String serverName) {
        return enabled && (servers.isEmpty() || servers.contains(serverName));
    }

    boolean allowed(Player player, PlaceholderRegistry placeholders, Map<String, Component> resolved) {
        return (permission == null || player.hasPermission(permission))
                && (condition == null || condition.matches(player, placeholders, resolved));
    }

    static Condition parseCondition(Map<?, ?> values, String location) {
        Object value = values.get("condition");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String expression)) {
            throw invalid(location, "condition", "must be text");
        }
        return Condition.parse(expression, location + ": 'condition'");
    }

    static String parsePermission(Map<?, ?> values, String location) {
        Object value = values.get("permission");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String permission) || permission.isBlank()) {
            throw invalid(location, "permission", "must be nonempty text");
        }
        return permission;
    }

    private static IllegalArgumentException invalid(String location, String setting, String issue) {
        return new IllegalArgumentException(location + ": '" + setting + "' " + issue);
    }
}
