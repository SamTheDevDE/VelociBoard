package de.samthedev.velociboard

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import java.nio.file.Path

data class BoardDefinition(
    val id: String,
    val enabled: Boolean,
    val servers: List<String>,
    val priority: Int,
    val title: String,
    val lines: List<BoardLine>,
    val condition: Condition?,
    val permission: String?,
) {
    fun id() = id
    fun enabled() = enabled
    fun servers() = servers
    fun priority() = priority
    fun title() = title
    fun lines() = lines
    fun condition() = condition
    fun permission() = permission

    fun matches(serverName: String): Boolean = enabled && (servers.isEmpty() || serverName in servers)

    fun allowed(player: Player, placeholders: PlaceholderRegistry, resolved: MutableMap<String, Component>): Boolean =
        (permission == null || player.hasPermission(permission)) &&
            (condition == null || condition.matches(player, placeholders, resolved))

    companion object {
        @JvmStatic
        fun load(file: Path, values: Map<*, *>): BoardDefinition {
            val name = file.fileName.toString()
            val location = "scoreboards/$name"
            val id = name.removeSuffix(".yml")
            val enabled = values.getOrDefault("enabled", true)
            require(enabled is Boolean) { invalid(location, "enabled", "must be true or false") }
            val servers = values.getOrDefault("servers", emptyList<String>())
            require(servers is List<*> && servers.all { it is String }) {
                invalid(location, "servers", "must be a list of server names")
            }
            val priority = values.getOrDefault("priority", 0)
            require(priority is Int) { invalid(location, "priority", "must be a number") }
            val title = values["title"]
            require(title is String) { invalid(location, "title", "must be text") }
            val lines = values["lines"]
            require(lines is List<*> && lines.size <= 15) {
                invalid(location, "lines", "must be a list of up to 15 entries")
            }
            val parsedLines = lines.mapIndexed { index, entry ->
                BoardLine.parse(entry, "$location: 'lines[$index]'")
            }
            return BoardDefinition(id, enabled, servers.filterIsInstance<String>(), priority, title, parsedLines,
                parseCondition(values, location), parsePermission(values, location))
        }

        @JvmStatic
        fun parseCondition(values: Map<*, *>, location: String): Condition? {
            val value = values["condition"] ?: return null
            require(value is String) { invalid(location, "condition", "must be text") }
            return Condition.parse(value, "$location: 'condition'")
        }

        @JvmStatic
        fun parsePermission(values: Map<*, *>, location: String): String? {
            val value = values["permission"] ?: return null
            require(value is String && value.isNotBlank()) { invalid(location, "permission", "must be nonempty text") }
            return value
        }

        private fun invalid(location: String, setting: String, issue: String) =
            "$location: '$setting' $issue"
    }
}
