package de.samthedev.velociboard;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

@Plugin(id = "velociboard", name = "VelociBoard", version = "0.1.0-SNAPSHOT",
        description = "Sidebar scoreboards for Velocity networks", authors = {"SamTheDevDE"},
        dependencies = {@Dependency(id = "velocity-scoreboard-api"), @Dependency(id = "luckperms", optional = true)})
public final class VelociBoard {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile BoardConfig config;
    private final PlaceholderRegistry placeholders;
    private final SidebarRenderer renderer;
    private final BackendBridge bridge;
    private ScheduledTask refreshTask;
    private long nextConditionCheck = Long.MIN_VALUE;

    @Inject
    public VelociBoard(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.placeholders = new PlaceholderRegistry(this::refreshPlayer);
        this.renderer = new SidebarRenderer(placeholders);
        this.bridge = new BackendBridge(proxy, logger, this::refreshPlayer);
        placeholders.setBackendResolver(bridge::value);
        placeholders.register("player_name", Player::getUsername);
        placeholders.register("player_uuid", player -> player.getUniqueId().toString());
        placeholders.register("server_name", player -> player.getCurrentServer()
                .map(connection -> connection.getServer().getServerInfo().getName()).orElse("unknown"));
        placeholders.registerPolled("server_online", Duration.ofSeconds(1), player -> player.getCurrentServer()
                .map(connection -> Integer.toString(connection.getServer().getPlayersConnected().size())).orElse("0"));
        placeholders.registerPolled("network_online", Duration.ofSeconds(1),
                player -> Integer.toString(proxy.getPlayerCount()));
        placeholders.registerPolled("ping", Duration.ofSeconds(5), player -> Long.toString(player.getPing()));
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        bridge.start(this);
        if (proxy.getPluginManager().getPlugin("luckperms").isPresent()) {
            try {
                LuckPermsPlaceholders.install(placeholders);
            } catch (IllegalStateException | LinkageError error) {
                logger.warn("LuckPerms placeholders unavailable: {}", error.getMessage());
                registerEmptyLuckPermsPlaceholders();
            }
        } else {
            registerEmptyLuckPermsPlaceholders();
        }
        reload();
        CommandMeta meta = proxy.getCommandManager().metaBuilder("velociboard")
                .aliases("vboard", "vb")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(meta, new BoardCommand());
        refreshTask = proxy.getScheduler().buildTask(this, () -> {
            BoardConfig current = config;
            boolean animated = current != null && current.animations().tick();
            boolean conditionsDue = false;
            long now = System.nanoTime();
            if (now >= nextConditionCheck) {
                nextConditionCheck = now + Duration.ofSeconds(1).toNanos();
                conditionsDue = current != null && current.hasConditions();
                bridge.expire();
            }
            for (Player player : proxy.getAllPlayers()) {
                if (placeholders.update(player, false) || animated || conditionsDue) {
                    renderer.refresh(player, current);
                }
            }
        }).repeat(Duration.ofMillis(50)).schedule();
        logger.info("VelociBoard started");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        bridge.stop(this);
    }

    @Subscribe
    public void onServerConnect(ServerPostConnectEvent event) {
        bridge.clearOnSwitch(event.getPlayer());
        placeholders.update(event.getPlayer(), true);
        renderer.refresh(event.getPlayer(), config);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        renderer.forget(event.getPlayer());
        placeholders.forget(event.getPlayer().getUniqueId());
        bridge.forget(event.getPlayer());
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        bridge.forget(event.getPlayer());
        renderer.refresh(event.getPlayer(), config);
    }

    private void refreshPlayer(UUID playerId) {
        proxy.getScheduler().buildTask(this, () -> proxy.getPlayer(playerId)
                .ifPresent(player -> renderer.refresh(player, config))).schedule();
    }

    private void registerEmptyLuckPermsPlaceholders() {
        placeholders.register("luckperms_prefix", player -> "");
        placeholders.register("luckperms_suffix", player -> "");
        placeholders.register("luckperms_primary_group", player -> "");
    }

    private boolean reload() {
        try {
            BoardConfig loaded = BoardConfig.load(dataDirectory);
            placeholders.configureRefresh(loaded.placeholderRefresh());
            config = loaded;
            for (var player : proxy.getAllPlayers()) {
                placeholders.update(player, true);
                renderer.refresh(player, config);
            }
            return true;
        } catch (IOException | IllegalArgumentException error) {
            logger.error("Failed to load VelociBoard configuration: {}", error.getMessage());
            return false;
        }
    }

    private final class BoardCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!invocation.source().hasPermission("velociboard.reload")) {
                    invocation.source().sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
                    return;
                }
                boolean loaded = reload();
                invocation.source().sendMessage(Component.text(
                        loaded ? "VelociBoard configuration reloaded." : "VelociBoard reload failed. Check the proxy log.",
                        loaded ? NamedTextColor.GREEN : NamedTextColor.RED));
                return;
            }
            invocation.source().sendMessage(Component.text("VelociBoard is in early development.", NamedTextColor.GRAY));
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            if (invocation.source().hasPermission("velociboard.reload")
                    && invocation.arguments().length <= 1
                    && "reload".startsWith(invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase())) {
                return List.of("reload");
            }
            return List.of();
        }
    }
}
