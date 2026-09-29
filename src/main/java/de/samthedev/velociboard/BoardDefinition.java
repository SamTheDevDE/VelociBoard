package de.samthedev.velociboard;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record BoardDefinition(String id, boolean enabled, List<String> servers, int priority, String title, List<String> lines) {
    static BoardDefinition load(Path file, Map<?, ?> values) {
        String name = file.getFileName().toString();
        String id = name.substring(0, name.length() - 4);
        Object enabled = values.containsKey("enabled") ? values.get("enabled") : Boolean.TRUE;
        if (!(enabled instanceof Boolean active)) {
            throw invalid(name, "enabled", "must be true or false");
        }
        Object servers = values.containsKey("servers") ? values.get("servers") : List.of();
        if (!(servers instanceof List<?> serverNames)
                || serverNames.stream().anyMatch(server -> !(server instanceof String))) {
            throw invalid(name, "servers", "must be a list of server names");
        }
        Object priority = values.containsKey("priority") ? values.get("priority") : 0;
        if (!(priority instanceof Integer order)) {
            throw invalid(name, "priority", "must be a number");
        }
        Object title = values.get("title");
        if (!(title instanceof String titleText)) {
            throw invalid(name, "title", "must be text");
        }
        Object lines = values.get("lines");
        if (!(lines instanceof List<?> entries) || entries.size() > 15
                || entries.stream().anyMatch(line -> !(line instanceof String))) {
            throw invalid(name, "lines", "must be a list of up to 15 text entries");
        }
        return new BoardDefinition(id, active, serverNames.stream().map(String.class::cast).toList(), order,
                titleText, entries.stream().map(String.class::cast).toList());
    }

    boolean matches(String serverName) {
        return enabled && (servers.isEmpty() || servers.contains(serverName));
    }

    private static IllegalArgumentException invalid(String file, String setting, String issue) {
        return new IllegalArgumentException("scoreboards/" + file + ": '" + setting + "' " + issue);
    }
}
