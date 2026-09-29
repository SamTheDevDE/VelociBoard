package de.samthedev.velociboard

import com.velocitypowered.api.TextHolder
import com.velocitypowered.api.network.ProtocolVersion
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.scoreboard.DisplaySlot
import com.velocitypowered.api.scoreboard.NumberFormat
import com.velocitypowered.api.scoreboard.ScoreboardManager
import net.kyori.adventure.text.Component
import java.util.UUID
import java.util.function.Predicate

class SidebarRenderer(private val placeholders: PlaceholderRegistry, private val visible: Predicate<Player>) {
    private val rendered = HashMap<UUID, RenderedBoard>()

    @Synchronized
    fun refresh(player: Player, config: BoardConfig?) = refresh(player, config, null)

    @Synchronized
    fun refresh(player: Player, config: BoardConfig?, preview: BoardDefinition?) {
        if (preview == null && !visible.test(player)) {
            remove(player)
            return
        }
        val serverName = player.currentServer.map { it.server.serverInfo.name }.orElse("unknown")
        val resolved = HashMap<String, Component>()
        val board = preview ?: config?.select(serverName) { it.allowed(player, placeholders, resolved) }
        if (player.protocolVersion.lessThan(ProtocolVersion.MINECRAFT_1_20_3) || board == null || config == null) {
            remove(player)
            return
        }

        val scoreboard = ScoreboardManager.getInstance().getProxyScoreboard(player)
        var previous = rendered[player.uniqueId]
        val title = placeholders.render(player, config.animations.apply(board.title), resolved)
        val oldObjective = scoreboard.getObjective(OBJECTIVE_NAME)
        val newObjective = oldObjective == null
        val objective = oldObjective ?: scoreboard.createObjective(OBJECTIVE_NAME) { builder ->
            builder.title(TextHolder.of(title)).displaySlot(DisplaySlot.SIDEBAR).numberFormat(NumberFormat.blank())
        }
        if (newObjective) previous = null

        val lines = board.lines.mapNotNull { line ->
            if (line.visible(player, placeholders, resolved))
                placeholders.render(player, config.animations.apply(line.text), resolved)
            else null
        }
        val oldLines = if (newObjective) emptyList() else previous?.lines
        val diff = BoardDiff.between(previous?.title, oldLines, title, lines)
        if (!newObjective && diff.titleChanged) objective.setTitle(TextHolder.of(title))
        for (slot in diff.removed) {
            val holder = BoardDiff.holder(slot)
            if (objective.getScore(holder) != null) objective.removeScore(holder)
        }
        for (update in diff.updated) {
            val holder = BoardDiff.holder(update.slot)
            val score = objective.getScore(holder)
            if (score == null) {
                objective.setScore(holder) { builder ->
                    builder.score(update.score).displayName(lines[update.slot]).numberFormat(NumberFormat.blank())
                }
            } else {
                if (update.scoreChanged) score.setScore(update.score)
                if (update.textChanged) score.setDisplayName(lines[update.slot])
            }
        }
        rendered[player.uniqueId] = RenderedBoard(title, lines.toList())
    }

    @Synchronized
    fun remove(player: Player) {
        rendered.remove(player.uniqueId)
        val scoreboard = ScoreboardManager.getInstance().getProxyScoreboard(player)
        if (scoreboard.getObjective(OBJECTIVE_NAME) != null) scoreboard.unregisterObjective(OBJECTIVE_NAME)
    }

    @Synchronized
    fun forget(player: Player) {
        rendered.remove(player.uniqueId)
    }

    private data class RenderedBoard(val title: Component, val lines: List<Component>)

    companion object {
        private const val OBJECTIVE_NAME = "velociboard"
    }
}
