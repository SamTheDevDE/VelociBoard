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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

final class SidebarRenderer {
    private static final String OBJECTIVE_NAME = "velociboard";
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, RenderedBoard> rendered = new HashMap<>();

    synchronized void refresh(Player player, BoardConfig config) {
        String serverName = player.getCurrentServer()
                .map(connection -> connection.getServer().getServerInfo().getName())
                .orElse("unknown");
        BoardDefinition board = config == null ? null : config.select(serverName);
        if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_20_3) || board == null) {
            remove(player);
            return;
        }

        ProxyScoreboard scoreboard = ScoreboardManager.getInstance().getProxyScoreboard(player);
        RenderedBoard previous = rendered.get(player.getUniqueId());
        ProxyObjective objective = scoreboard.getObjective(OBJECTIVE_NAME);
        Component title = render(player, serverName, board.title());
        if (objective == null) {
            objective = scoreboard.createObjective(OBJECTIVE_NAME, builder -> builder
                    .title(TextHolder.of(title))
                    .displaySlot(DisplaySlot.SIDEBAR)
                    .numberFormat(NumberFormat.blank()));
            previous = null;
        } else if (previous == null || !title.equals(previous.title())) {
            objective.setTitle(TextHolder.of(title));
        }

        List<Component> lines = new ArrayList<>(board.lines().size());
        for (String line : board.lines()) {
            lines.add(render(player, serverName, line));
        }
        for (int index = 0; index < lines.size(); index++) {
            Component line = lines.get(index);
            String holder = holder(index);
            ProxyScore score = objective.getScore(holder);
            int position = lines.size() - index;
            if (score == null) {
                objective.setScore(holder, builder -> builder.score(position)
                        .displayName(line).numberFormat(NumberFormat.blank()));
            } else {
                if (score.getScore() != position) {
                    score.setScore(position);
                }
                if (previous == null || index >= previous.lines().size() || !line.equals(previous.lines().get(index))) {
                    score.setDisplayName(line);
                }
            }
        }
        int oldCount = previous == null ? 15 : previous.lines().size();
        for (int index = lines.size(); index < oldCount; index++) {
            if (objective.getScore(holder(index)) != null) {
                objective.removeScore(holder(index));
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

    private Component render(Player player, String serverName, String text) {
        String template = text.replace("%player_name%", "<player_name>")
                .replace("%server_name%", "<server_name>");
        return miniMessage.deserialize(template,
                Placeholder.unparsed("player_name", player.getUsername()),
                Placeholder.unparsed("server_name", serverName));
    }

    private static String holder(int index) {
        return "vb_" + index;
    }

    private record RenderedBoard(Component title, List<Component> lines) {
    }
}
