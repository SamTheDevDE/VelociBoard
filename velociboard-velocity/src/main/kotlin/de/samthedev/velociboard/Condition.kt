package de.samthedev.velociboard

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import java.math.BigDecimal

internal data class Condition(val placeholder: String, val operator: String, val expected: String, val number: BigDecimal?) {
    fun matches(player: Player, placeholders: PlaceholderRegistry, resolved: MutableMap<String, Component>): Boolean {
        val actual = placeholders.resolveText(player, placeholder, resolved) ?: return false
        val actualNumber = actual.toBigDecimalOrNull()
        val comparison = if (number != null && actualNumber != null) actualNumber.compareTo(number)
            else actual.compareTo(expected)
        return when (operator) {
            "==" -> comparison == 0
            "!=" -> comparison != 0
            ">" -> actualNumber != null && comparison > 0
            "<" -> actualNumber != null && comparison < 0
            ">=" -> actualNumber != null && comparison >= 0
            "<=" -> actualNumber != null && comparison <= 0
            else -> false
        }
    }

    companion object {
        private val expression = Regex("^%([a-z][a-z0-9_]*)%\\s*(==|!=|>=|<=|>|<)\\s*(.+)$")

        fun parse(text: String, location: String): Condition {
            val match = expression.matchEntire(text.trim())
                ?: throw IllegalArgumentException("$location: expected '%placeholder% operator value'")
            var right = match.groupValues[3].trim()
            if (right.length >= 2 && ((right.startsWith('"') && right.endsWith('"'))
                        || (right.startsWith('\'') && right.endsWith('\'')))) {
                right = right.substring(1, right.length - 1)
            }
            val operator = match.groupValues[2]
            val number = right.toBigDecimalOrNull()
            require(operator == "==" || operator == "!=" || number != null) {
                "$location: numeric comparison needs a number on the right"
            }
            return Condition(match.groupValues[1], operator, right, number)
        }
    }
}
