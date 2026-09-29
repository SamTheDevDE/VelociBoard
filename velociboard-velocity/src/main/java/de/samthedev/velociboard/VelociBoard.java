package de.samthedev.velociboard;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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
    private final PreferenceStore preferences;
    private final Map<UUID, PreferenceState> preferenceStates = new ConcurrentHashMap<>();
    private ScheduledTask refreshTask;
    private long nextConditionCheck = Long.MIN_VALUE;
    private volatile boolean stopping;

    @Inject
    public VelociBoard(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.placeholders = new PlaceholderRegistry(this::refreshPlayer);
        this.renderer = new SidebarRenderer(placeholders, this::wantsBoard);
        this.bridge = new BackendBridge(proxy, logger, this::refreshPlayer);
        this.preferences = new PreferenceStore(dataDirectory.resolve("preferences.db"), logger);
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
        preferences.start();
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
        CommandMeta scoreboard = proxy.getCommandManager().metaBuilder("scoreboard").plugin(this).build();
        proxy.getCommandManager().register(scoreboard, (SimpleCommand) invocation -> toggle(invocation.source()));
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
        stopping = true;
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        bridge.stop(this);
        preferences.close();
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        preferenceStates.put(id, new Loading(player, 0));
        preferences.load(id).thenAccept(saved -> {
            if (!stopping) {
                proxy.getScheduler().buildTask(this, () -> applyLoadedPreference(player, saved)).schedule();
            }
        });
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
        preferenceStates.computeIfPresent(event.getPlayer().getUniqueId(),
                (id, state) -> state.player() == event.getPlayer() ? null : state);
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        bridge.forget(event.getPlayer());
        renderer.refresh(event.getPlayer(), config);
    }

    private void refreshPlayer(UUID playerId) {
        if (stopping) {
            return;
        }
        proxy.getScheduler().buildTask(this, () -> proxy.getPlayer(playerId)
                .ifPresent(player -> renderer.refresh(player, config))).schedule();
    }

    private boolean wantsBoard(Player player) {
        PreferenceState state = preferenceStates.get(player.getUniqueId());
        return state instanceof Ready ready && ready.player() == player && !ready.hidden();
    }

    private void applyLoadedPreference(Player player, boolean savedHidden) {
        UUID id = player.getUniqueId();
        AtomicReference<Loading> loaded = new AtomicReference<>();
        preferenceStates.computeIfPresent(id, (ignored, state) -> {
            if (state instanceof Loading pending && pending.player() == player) {
                loaded.set(pending);
                boolean hidden = savedHidden ^ (pending.toggles() % 2 != 0);
                if (pending.toggles() != 0) {
                    preferences.save(id, hidden);
                }
                return new Ready(player, hidden);
            }
            return state;
        });
        if (loaded.get() != null) {
            if (player.getCurrentServer().isPresent()) {
                renderer.refresh(player, config);
            }
            if (loaded.get().toggles() != 0) {
                sendToggleResult(player, !wantsBoard(player));
            }
        }
    }

    private void toggle(CommandSource source) {
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text("Only players can toggle their scoreboard.", NamedTextColor.RED));
            return;
        }
        if (!player.hasPermission("velociboard.toggle")) {
            player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return;
        }
        UUID id = player.getUniqueId();
        AtomicReference<Boolean> changed = new AtomicReference<>();
        preferenceStates.compute(id, (ignored, state) -> {
            if (state instanceof Loading pending && pending.player() == player) {
                return new Loading(player, pending.toggles() + 1);
            }
            boolean hidden = !(state instanceof Ready ready && ready.player() == player && ready.hidden());
            preferences.save(id, hidden);
            changed.set(hidden);
            return new Ready(player, hidden);
        });
        if (changed.get() != null) {
            renderer.refresh(player, config);
            sendToggleResult(player, changed.get());
        }
    }

    private void sendToggleResult(Player player, boolean hidden) {
        player.sendMessage(Component.text(hidden ? "Scoreboard hidden." : "Scoreboard shown.", NamedTextColor.GREEN));
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
            if (args.length == 1 && args[0].equalsIgnoreCase("toggle")) {
                toggle(invocation.source());
                return;
            }
            invocation.source().sendMessage(Component.text("VelociBoard is in early development.", NamedTextColor.GRAY));
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            if (invocation.arguments().length <= 1) {
                String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase();
                List<String> options = new java.util.ArrayList<>();
                if (invocation.source().hasPermission("velociboard.reload") && "reload".startsWith(prefix)) {
                    options.add("reload");
                }
                if (invocation.source().hasPermission("velociboard.toggle") && "toggle".startsWith(prefix)) {
                    options.add("toggle");
                }
                return options;
            }
            return List.of();
        }
    }

    private sealed interface PreferenceState permits Loading, Ready {
        Player player();
    }

    private record Loading(Player player, int toggles) implements PreferenceState {
    }

    private record Ready(Player player, boolean hidden) implements PreferenceState {
    }
}
