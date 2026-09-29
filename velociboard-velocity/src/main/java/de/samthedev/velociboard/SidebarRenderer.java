package de.samthedev.velociboard;

import com.velocitypowered.api.TextHolder;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scoreboard.DisplaySlot;
import com.velocitypowered.api.scoreboard.NumberFormat;
import com.velocitypowered.api.scoreboard.ProxyObjective;
import com.velocitypowered.api.scoreboard.ProxyScore;
import com.velocitypowered.api.scoreboard.ProxyScoreboard;
import com.velocitypowered.api.scoreboard.ScoreboardManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;

final class SidebarRenderer {
    private static final String OBJECTIVE_NAME = "velociboard";
    private final PlaceholderRegistry placeholders;
    private final Predicate<Player> visible;
    private final Map<UUID, RenderedBoard> rendered = new HashMap<>();

    SidebarRenderer(PlaceholderRegistry placeholders, Predicate<Player> visible) {
        this.placeholders = placeholders;
        this.visible = visible;
    }

    synchronized void refresh(Player player, BoardConfig config) {
        if (!visible.test(player)) {
            remove(player);
            return;
        }
        String serverName = player.getCurrentServer()
                .map(connection -> connection.getServer().getServerInfo().getName())
                .orElse("unknown");
        Map<String, Component> resolved = new HashMap<>();
        BoardDefinition board = config == null ? null
                : config.select(serverName, candidate -> candidate.allowed(player, placeholders, resolved));
        if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_20_3) || board == null) {
            remove(player);
            return;
        }

        ProxyScoreboard scoreboard = ScoreboardManager.getInstance().getProxyScoreboard(player);
        RenderedBoard previous = rendered.get(player.getUniqueId());
        ProxyObjective objective = scoreboard.getObjective(OBJECTIVE_NAME);
        Component title = placeholders.render(player, config.animations().apply(board.title()), resolved);
        boolean newObjective = objective == null;
        if (objective == null) {
            objective = scoreboard.createObjective(OBJECTIVE_NAME, builder -> builder
                    .title(TextHolder.of(title))
                    .displaySlot(DisplaySlot.SIDEBAR)
                    .numberFormat(NumberFormat.blank()));
            previous = null;
        }

        List<Component> lines = new ArrayList<>(board.lines().size());
        for (BoardLine line : board.lines()) {
            if (line.visible(player, placeholders, resolved)) {
                lines.add(placeholders.render(player, config.animations().apply(line.text()), resolved));
            }
        }
        List<Component> oldLines = newObjective ? List.of() : previous == null ? null : previous.lines();
        BoardDiff diff = BoardDiff.between(previous == null ? null : previous.title(), oldLines, title, lines);
        if (!newObjective && diff.titleChanged()) {
            objective.setTitle(TextHolder.of(title));
        }
        for (int slot : diff.removed()) {
            String holder = BoardDiff.holder(slot);
            if (objective.getScore(holder) != null) {
                objective.removeScore(holder);
            }
        }
        for (BoardDiff.LineUpdate update : diff.updated()) {
            String holder = BoardDiff.holder(update.slot());
            ProxyScore score = objective.getScore(holder);
            if (score == null) {
                objective.setScore(holder, builder -> builder.score(update.score())
                        .displayName(lines.get(update.slot())).numberFormat(NumberFormat.blank()));
            } else {
                if (update.scoreChanged()) {
                    score.setScore(update.score());
                }
                if (update.textChanged()) {
                    score.setDisplayName(lines.get(update.slot()));
                }
            }
        }
        rendered.put(player.getUniqueId(), new RenderedBoard(title, List.copyOf(lines)));
    }

    synchronized void remove(Player player) {
        rendered.remove(player.getUniqueId());
        ProxyScoreboard scoreboard = ScoreboardManager.getInstance().getProxyScoreboard(player);
        if (scoreboard.getObjective(OBJECTIVE_NAME) != null) {
            scoreboard.unregisterObjective(OBJECTIVE_NAME);
        }
    }

    synchronized void forget(Player player) {
        rendered.remove(player.getUniqueId());
    }

    private record RenderedBoard(Component title, List<Component> lines) {
    }
}
