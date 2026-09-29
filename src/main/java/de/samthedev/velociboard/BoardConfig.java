package de.samthedev.velociboard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

record BoardConfig(boolean enabled, String title, List<String> lines) {
    private static final String DEFAULT_TITLE = "<purple><bold>VelociBoard</bold></purple>";
    private static final List<String> DEFAULT_LINES = List.of(
            "", "<gray>Player", "<white>%player_name%", "", "<gray>Server", "<white>%server_name%");

    static BoardConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.yml");
        if (Files.notExists(file)) {
            try (InputStream defaultConfig = BoardConfig.class.getResourceAsStream("/config.yml")) {
                if (defaultConfig == null) {
                    throw new IOException("Bundled config.yml is missing");
                }
                Files.copy(defaultConfig, file);
            }
        }

        try (InputStream input = Files.newInputStream(file)) {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (!(parsed instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException("root must be a mapping");
            }
            Object enabled = values.get("enabled");
            if (!(enabled instanceof Boolean value)) {
                throw new IllegalArgumentException("'enabled' must be true or false");
            }
            Object title = values.containsKey("title") ? values.get("title") : DEFAULT_TITLE;
            if (!(title instanceof String titleText)) {
                throw new IllegalArgumentException("'title' must be text");
            }
            Object lines = values.containsKey("lines") ? values.get("lines") : DEFAULT_LINES;
            if (!(lines instanceof List<?> entries) || entries.size() > 15
                    || entries.stream().anyMatch(line -> !(line instanceof String))) {
                throw new IllegalArgumentException("'lines' must be a list of up to 15 text entries");
            }
            return new BoardConfig(value, titleText, entries.stream().map(String.class::cast).toList());
        } catch (YAMLException error) {
            throw new IllegalArgumentException("invalid YAML: " + error.getMessage(), error);
        }
    }
}
