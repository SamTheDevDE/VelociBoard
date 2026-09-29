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
import de.samthedev.velociboard.api.VelociBoardAPI;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

@Plugin(id = "velociboard", name = "VelociBoard", version = BuildVersion.VALUE,
        description = "Sidebar scoreboards for Velocity networks", authors = {"SamTheDevDE"},
        dependencies = {@Dependency(id = "velocity-scoreboard-api"), @Dependency(id = "luckperms", optional = true)})
public final class VelociBoard {
    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z][a-z0-9_]*)%");
    private static final String HIDDEN_OVERRIDE = "\u0000hidden";
    private static volatile VelociBoardAPI api;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile BoardConfig config;
    private final PlaceholderRegistry placeholders;
    private final SidebarRenderer renderer;
    private final BackendBridge bridge;
    private final PreferenceStore preferences;
    private final VelociBoardAPI publicApi = new PluginApi();
    private final Map<UUID, PreferenceState> preferenceStates = new ConcurrentHashMap<>();
    private final Map<UUID, String> previews = new ConcurrentHashMap<>();
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

    /** Returns the API after Velocity has initialized the plugin. */
    public static VelociBoardAPI getApi() {
        VelociBoardAPI current = api;
        if (current == null) {
            throw new IllegalStateException("VelociBoard is not running");
        }
        return current;
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
                    refreshBoard(player, current);
                }
            }
        }).repeat(Duration.ofMillis(50)).schedule();
        api = publicApi;
        logger.info("VelociBoard started");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        stopping = true;
        api = null;
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
        previews.remove(event.getPlayer().getUniqueId());
        bridge.clearOnSwitch(event.getPlayer());
        placeholders.update(event.getPlayer(), true);
        refreshBoard(event.getPlayer(), config);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        renderer.forget(event.getPlayer());
        placeholders.forget(event.getPlayer().getUniqueId());
        bridge.forget(event.getPlayer());
        previews.remove(event.getPlayer().getUniqueId());
        preferenceStates.computeIfPresent(event.getPlayer().getUniqueId(),
                (id, state) -> state.player() == event.getPlayer() ? null : state);
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        bridge.forget(event.getPlayer());
        refreshBoard(event.getPlayer(), config);
    }

    private void refreshPlayer(UUID playerId) {
        if (stopping) {
            return;
        }
        proxy.getScheduler().buildTask(this, () -> proxy.getPlayer(playerId)
                .ifPresent(player -> refreshBoard(player, config))).schedule();
    }

    private void refreshBoard(Player player, BoardConfig current) {
        String previewId = previews.get(player.getUniqueId());
        if (HIDDEN_OVERRIDE.equals(previewId)) {
            renderer.remove(player);
            return;
        }
        BoardDefinition preview = current == null || previewId == null ? null : current.boards().stream()
                .filter(board -> board.id().equals(previewId)).findFirst().orElse(null);
        renderer.refresh(player, current, preview);
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
                refreshBoard(player, config);
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
            previews.remove(id);
            refreshBoard(player, config);
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
                refreshBoard(player, config);
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
            CommandSource source = invocation.source();
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!permitted(source, "velociboard.reload")) {
                    return;
                }
                boolean loaded = reload();
                source.sendMessage(Component.text(
                        loaded ? "VelociBoard configuration reloaded." : "VelociBoard reload failed. Check the proxy log.",
                        loaded ? NamedTextColor.GREEN : NamedTextColor.RED));
                return;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("toggle")) {
                toggle(source);
                return;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
                if (!permitted(source, "velociboard.admin")) {
                    return;
                }
                BoardConfig current = config;
                source.sendMessage(Component.text("Boards: " + (current == null ? "none" : current.boards().stream()
                        .map(board -> board.id() + " (priority " + board.priority() + ")")
                        .reduce((left, right) -> left + ", " + right).orElse("none")), NamedTextColor.GRAY));
                return;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("preview")) {
                if (!permitted(source, "velociboard.preview")) {
                    return;
                }
                if (!(source instanceof Player player)) {
                    source.sendMessage(Component.text("Only players can preview a board.", NamedTextColor.RED));
                    return;
                }
                BoardConfig current = config;
                BoardDefinition board = current == null ? null : current.boards().stream()
                        .filter(candidate -> candidate.id().equalsIgnoreCase(args[1])).findFirst().orElse(null);
                if (board == null) {
                    source.sendMessage(Component.text("Unknown board: " + args[1], NamedTextColor.RED));
                    return;
                }
                previews.put(player.getUniqueId(), board.id());
                refreshBoard(player, current);
                source.sendMessage(Component.text("Previewing " + board.id() + " until you switch servers or toggle.",
                        NamedTextColor.GREEN));
                return;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("placeholders")) {
                if (!permitted(source, "velociboard.admin")) {
                    return;
                }
                source.sendMessage(Component.text("Placeholders: " + placeholders.names().stream()
                        .map(name -> "%" + name + "%").reduce((left, right) -> left + ", " + right).orElse("none"),
                        NamedTextColor.GRAY));
                source.sendMessage(Component.text("Paper bridge values use %backend_<name>%.", NamedTextColor.GRAY));
                return;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
                if (!permitted(source, "velociboard.debug")) {
                    return;
                }
                if (!(source instanceof Player player)) {
                    source.sendMessage(Component.text("Only players can inspect their board.", NamedTextColor.RED));
                    return;
                }
                debug(player);
                return;
            }
            source.sendMessage(Component.text("/velociboard <reload|toggle|list|preview|placeholders|debug>",
                    NamedTextColor.GRAY));
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            if (invocation.arguments().length <= 1) {
                String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase();
                List<String> options = new ArrayList<>();
                if (invocation.source().hasPermission("velociboard.reload") && "reload".startsWith(prefix)) {
                    options.add("reload");
                }
                if (invocation.source().hasPermission("velociboard.toggle") && "toggle".startsWith(prefix)) {
                    options.add("toggle");
                }
                for (String command : List.of("list", "placeholders")) {
                    if (invocation.source().hasPermission("velociboard.admin") && command.startsWith(prefix)) {
                        options.add(command);
                    }
                }
                if (invocation.source().hasPermission("velociboard.preview") && "preview".startsWith(prefix)) {
                    options.add("preview");
                }
                if (invocation.source().hasPermission("velociboard.debug") && "debug".startsWith(prefix)) {
                    options.add("debug");
                }
                return options;
            }
            if (invocation.arguments().length == 2 && invocation.arguments()[0].equalsIgnoreCase("preview")
                    && invocation.source().hasPermission("velociboard.preview") && config != null) {
                String prefix = invocation.arguments()[1].toLowerCase();
                return config.boards().stream().map(BoardDefinition::id).filter(id -> id.startsWith(prefix)).toList();
            }
            return List.of();
        }
    }

    private boolean permitted(CommandSource source, String permission) {
        if (source.hasPermission(permission)) {
            return true;
        }
        source.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
        return false;
    }

    private void debug(Player player) {
        BoardConfig current = config;
        if (current == null) {
            player.sendMessage(Component.text("No configuration loaded.", NamedTextColor.RED));
            return;
        }
        String server = player.getCurrentServer()
                .map(connection -> connection.getServer().getServerInfo().getName()).orElse("unknown");
        Map<String, Component> resolved = new HashMap<>();
        BoardDefinition active = current.select(server, board -> board.allowed(player, placeholders, resolved));
        player.sendMessage(Component.text("Board: " + (active == null ? "none" : active.id())
                + (wantsBoard(player) ? "" : " (hidden)"), NamedTextColor.GRAY));
        for (BoardDefinition board : current.boards()) {
            String reason = !board.matches(server) ? "server/disabled"
                    : !board.allowed(player, placeholders, resolved) ? "condition/permission" : "matched";
            player.sendMessage(Component.text(board.id() + ": " + reason + " (priority " + board.priority() + ")",
                    NamedTextColor.GRAY));
        }
        if (active != null) {
            for (int index = 0; index < active.lines().size(); index++) {
                BoardLine line = active.lines().get(index);
                if (line.condition() != null || line.permission() != null) {
                    player.sendMessage(Component.text("line " + (index + 1) + ": "
                            + (line.visible(player, placeholders, resolved) ? "shown" : "hidden"), NamedTextColor.GRAY));
                }
            }
            String templates = active.title() + " " + active.lines().stream()
                    .map(BoardLine::text).reduce("", (left, right) -> left + " " + right);
            Matcher matcher = PLACEHOLDER.matcher(templates);
            java.util.Set<String> names = new java.util.TreeSet<>();
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            for (String name : names) {
                player.sendMessage(Component.text("%" + name + "% = " + placeholders.debugValue(player, name),
                        NamedTextColor.GRAY));
            }
        }
        player.sendMessage(Component.text("Bridge: " + bridge.describe(player), NamedTextColor.GRAY));
    }

    private final class PluginApi implements VelociBoardAPI {
        private final Placeholders placeholdersApi = new PlaceholderAccess();

        @Override
        public Placeholders placeholders() {
            return placeholdersApi;
        }

        @Override
        public boolean showBoard(Player player, String boardId) {
            BoardConfig current = config;
            if (stopping || current == null || proxy.getPlayer(player.getUniqueId()).orElse(null) != player) {
                return false;
            }
            BoardDefinition board = current.boards().stream().filter(candidate -> candidate.id().equals(boardId))
                    .findFirst().orElse(null);
            if (board == null) {
                return false;
            }
            previews.put(player.getUniqueId(), board.id());
            refreshPlayer(player.getUniqueId());
            return true;
        }

        @Override
        public void hideBoard(Player player) {
            if (!stopping && proxy.getPlayer(player.getUniqueId()).orElse(null) == player) {
                previews.put(player.getUniqueId(), HIDDEN_OVERRIDE);
                refreshPlayer(player.getUniqueId());
            }
        }

        @Override
        public void refresh(Player player) {
            if (proxy.getPlayer(player.getUniqueId()).orElse(null) == player) {
                refreshPlayer(player.getUniqueId());
            }
        }

        private final class PlaceholderAccess implements Placeholders {
            private final Set<String> owned = new HashSet<>();

            @Override
            public synchronized void register(String name, java.util.function.Function<Player, String> resolver) {
                placeholders.register(name, resolver);
                owned.add(name);
                refreshAll();
            }

            @Override
            public synchronized void registerCached(String name, Duration interval,
                    java.util.function.Function<Player, java.util.concurrent.CompletionStage<String>> resolver) {
                placeholders.registerCached(name, interval, resolver);
                owned.add(name);
                refreshAll();
            }

            @Override
            public synchronized void unregister(String name) {
                if (owned.remove(name)) {
                    placeholders.unregister(name);
                    refreshAll();
                }
            }

            private void refreshAll() {
                for (Player player : proxy.getAllPlayers()) {
                    refreshPlayer(player.getUniqueId());
                }
            }
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
