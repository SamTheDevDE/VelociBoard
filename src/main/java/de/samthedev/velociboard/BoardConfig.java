package de.samthedev.velociboard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

record BoardConfig(boolean enabled) {
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
            return new BoardConfig(value);
        } catch (YAMLException error) {
            throw new IllegalArgumentException("invalid YAML: " + error.getMessage(), error);
        }
    }
}
