package de.samthedev.velociboard

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class BoardConfig(
    val enabled: Boolean,
    val boards: List<BoardDefinition>,
    val placeholderRefresh: Map<String, Duration>,
    val animations: Animations,
) {
    fun enabled() = enabled
    fun boards() = boards
    fun placeholderRefresh() = placeholderRefresh
    fun animations() = animations

    fun select(serverName: String): BoardDefinition? = select(serverName) { true }

    fun select(serverName: String, allowed: java.util.function.Predicate<BoardDefinition>): BoardDefinition? {
        if (!enabled) return null
        return boards.filter { it.matches(serverName) && allowed.test(it) }
            .maxWithOrNull(compareBy<BoardDefinition> { it.priority }.thenByDescending { it.id })
    }

    fun hasConditions() = boards.any { board ->
        board.condition != null || board.permission != null || board.lines.any {
            it.condition != null || it.permission != null
        }
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun load(dataDirectory: Path): BoardConfig {
            Files.createDirectories(dataDirectory)
            val globalFile = dataDirectory.resolve("config.yml")
            if (Files.notExists(globalFile)) copyResource("config.yml", globalFile)
            val global = readYaml(globalFile)
            val enabled = global["enabled"]
            require(enabled is Boolean) { "config.yml: 'enabled' must be true or false" }
            val refresh = linkedMapOf(
                "server_online" to Duration.ofMillis(1000),
                "network_online" to Duration.ofMillis(1000),
                "ping" to Duration.ofMillis(5000),
                "luckperms_prefix" to Duration.ofMillis(1000),
                "luckperms_suffix" to Duration.ofMillis(1000),
                "luckperms_primary_group" to Duration.ofMillis(1000),
            )
            val customRefresh = global["placeholder-refresh"]
            if (customRefresh != null) {
                require(customRefresh is Map<*, *>) { "config.yml: 'placeholder-refresh' must be a mapping" }
                for ((key, value) in customRefresh) {
                    require(key is String && refresh.containsKey(key)) {
                        "config.yml: unknown placeholder-refresh entry '$key'"
                    }
                    require(value is Number && value.toLong() >= 50 && value.toDouble() == value.toLong().toDouble()) {
                        "config.yml: 'placeholder-refresh.$key' must be whole milliseconds (at least 50)"
                    }
                    refresh[key] = Duration.ofMillis(value.toLong())
                }
            }

            val boardsDirectory = dataDirectory.resolve("scoreboards")
            if (Files.notExists(boardsDirectory)) {
                Files.createDirectories(boardsDirectory)
                if (global.containsKey("title") || global.containsKey("lines")) {
                    val migrated = linkedMapOf<String, Any?>(
                        "enabled" to true,
                        "priority" to 0,
                        "title" to global["title"],
                        "lines" to global["lines"],
                    )
                    Files.newBufferedWriter(boardsDirectory.resolve("default.yml")).use { Yaml().dump(migrated, it) }
                } else {
                    copyResource("scoreboards/default.yml", boardsDirectory.resolve("default.yml"))
                }
                copyResource("scoreboards/lobby.yml", boardsDirectory.resolve("lobby.yml"))
            }
            require(Files.isDirectory(boardsDirectory)) { "scoreboards: must be a directory" }
            val boards = Files.list(boardsDirectory).use { files ->
                files.filter { it.fileName.toString().endsWith(".yml") }
                    .sorted(compareBy { it.fileName.toString() })
                    .map { BoardDefinition.load(it, readYaml(it)) }
                    .toList()
            }
            val animationsFile = dataDirectory.resolve("animations.yml")
            if (Files.notExists(animationsFile)) copyResource("animations.yml", animationsFile)
            val animations = Animations.load(animationsFile)
            for (board in boards) {
                animations.validate(board.id, "title", board.title)
                board.lines.forEachIndexed { index, line ->
                    animations.validate(board.id, "lines[$index]", line.text)
                }
            }
            return BoardConfig(enabled, boards, refresh.toMap(), animations)
        }

        @JvmStatic
        fun readYaml(file: Path): Map<*, *> {
            val name = if (file.parent.fileName.toString() == "scoreboards")
                "scoreboards/${file.fileName}" else file.fileName.toString()
            try {
                Files.newInputStream(file).use { input ->
                    val parsed = Yaml(SafeConstructor(LoaderOptions())).load<Any>(input)
                    require(parsed is Map<*, *>) { "root must be a mapping" }
                    return parsed
                }
            } catch (error: IOException) {
                throw IllegalArgumentException("$name: ${error.message}", error)
            } catch (error: YAMLException) {
                throw IllegalArgumentException("$name: ${error.message}", error)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("$name: ${error.message}", error)
            }
        }

        private fun copyResource(resource: String, destination: Path) {
            val input = BoardConfig::class.java.getResourceAsStream("/$resource")
                ?: throw IOException("Bundled $resource is missing")
            input.use { Files.copy(it, destination) }
        }
    }
}
