package de.samthedev.velociboard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

record BoardConfig(boolean enabled, List<BoardDefinition> boards, Map<String, Duration> placeholderRefresh,
        Animations animations) {
    static BoardConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path globalFile = dataDirectory.resolve("config.yml");
        if (Files.notExists(globalFile)) {
            copyResource("config.yml", globalFile);
        }
        Map<?, ?> global = readYaml(globalFile);
        Object enabled = global.get("enabled");
        if (!(enabled instanceof Boolean active)) {
            throw new IllegalArgumentException("config.yml: 'enabled' must be true or false");
        }
        Map<String, Duration> refresh = new LinkedHashMap<>();
        refresh.put("server_online", Duration.ofMillis(1000));
        refresh.put("network_online", Duration.ofMillis(1000));
        refresh.put("ping", Duration.ofMillis(5000));
        refresh.put("luckperms_prefix", Duration.ofMillis(1000));
        refresh.put("luckperms_suffix", Duration.ofMillis(1000));
        refresh.put("luckperms_primary_group", Duration.ofMillis(1000));
        Object customRefresh = global.get("placeholder-refresh");
        if (customRefresh != null) {
            if (!(customRefresh instanceof Map<?, ?> settings)) {
                throw new IllegalArgumentException("config.yml: 'placeholder-refresh' must be a mapping");
            }
            for (var entry : settings.entrySet()) {
                if (!(entry.getKey() instanceof String name) || !refresh.containsKey(name)) {
                    throw new IllegalArgumentException("config.yml: unknown placeholder-refresh entry '" + entry.getKey() + "'");
                }
                if (!(entry.getValue() instanceof Number milliseconds)
                        || milliseconds.longValue() < 50
                        || milliseconds.doubleValue() != milliseconds.longValue()) {
                    throw new IllegalArgumentException("config.yml: 'placeholder-refresh." + name
                            + "' must be whole milliseconds (at least 50)");
                }
                refresh.put(name, Duration.ofMillis(milliseconds.longValue()));
            }
        }

        Path boardsDirectory = dataDirectory.resolve("scoreboards");
        if (Files.notExists(boardsDirectory)) {
            Files.createDirectories(boardsDirectory);
            if (global.containsKey("title") || global.containsKey("lines")) {
                Map<String, Object> migrated = new LinkedHashMap<>();
                migrated.put("enabled", true);
                migrated.put("priority", 0);
                migrated.put("title", global.get("title"));
                migrated.put("lines", global.get("lines"));
                try (var output = Files.newBufferedWriter(boardsDirectory.resolve("default.yml"))) {
                    new Yaml().dump(migrated, output);
                }
            } else {
                copyResource("scoreboards/default.yml", boardsDirectory.resolve("default.yml"));
            }
            copyResource("scoreboards/lobby.yml", boardsDirectory.resolve("lobby.yml"));
        }
        if (!Files.isDirectory(boardsDirectory)) {
            throw new IllegalArgumentException("scoreboards: must be a directory");
        }

        List<BoardDefinition> boards;
        try (Stream<Path> files = Files.list(boardsDirectory)) {
            boards = files.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .map(file -> BoardDefinition.load(file, readYaml(file)))
                    .toList();
        }
        Path animationsFile = dataDirectory.resolve("animations.yml");
        if (Files.notExists(animationsFile)) {
            copyResource("animations.yml", animationsFile);
        }
        Animations animations = Animations.load(animationsFile);
        for (BoardDefinition board : boards) {
            animations.validate(board.id(), "title", board.title());
            for (int index = 0; index < board.lines().size(); index++) {
                animations.validate(board.id(), "lines[" + index + "]", board.lines().get(index));
            }
        }
        return new BoardConfig(active, boards, Map.copyOf(refresh), animations);
    }

    BoardDefinition select(String serverName) {
        if (!enabled) {
            return null;
        }
        return boards.stream().filter(board -> board.matches(serverName))
                .max(Comparator.comparingInt(BoardDefinition::priority)
                        .thenComparing(BoardDefinition::id, Comparator.reverseOrder()))
                .orElse(null);
    }

    static Map<?, ?> readYaml(Path file) {
        String name = file.getParent().getFileName().toString().equals("scoreboards")
                ? "scoreboards/" + file.getFileName() : file.getFileName().toString();
        try (InputStream input = Files.newInputStream(file)) {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (!(parsed instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("root must be a mapping");
            }
            return values;
        } catch (IOException | YAMLException error) {
            throw new IllegalArgumentException(name + ": " + error.getMessage(), error);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(name + ": " + error.getMessage(), error);
        }
    }

    private static void copyResource(String resource, Path destination) throws IOException {
        try (InputStream input = BoardConfig.class.getResourceAsStream("/" + resource)) {
            if (input == null) {
                throw new IOException("Bundled " + resource + " is missing");
            }
            Files.copy(input, destination);
        }
    }
}
