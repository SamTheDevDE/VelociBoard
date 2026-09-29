package de.samthedev.velociboard

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component

internal data class BoardLine(val text: String, val condition: Condition?, val permission: String?) {
    fun visible(player: Player, placeholders: PlaceholderRegistry, resolved: MutableMap<String, Component>): Boolean =
        (permission == null || player.hasPermission(permission)) &&
            (condition == null || condition.matches(player, placeholders, resolved))

    companion object {
        fun parse(value: Any?, location: String): BoardLine {
            if (value is String) return BoardLine(value, null, null)
            require(value is Map<*, *>) { "$location: must be text or a mapping" }
            val text = value["text"]
            require(text is String) { "$location.text: must be text" }
            return BoardLine(text, BoardDefinition.parseCondition(value, location),
                BoardDefinition.parsePermission(value, location))
        }
    }
}
