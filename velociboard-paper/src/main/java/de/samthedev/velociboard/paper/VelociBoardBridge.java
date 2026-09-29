package de.samthedev.velociboard.paper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.bukkit.entity.Player;

public final class VelociBoardBridge {
    private static final int MAX_CUSTOM_VALUES = 12;
    private static final Map<String, Function<Player, String>> immediate = new ConcurrentHashMap<>();
    private static final Map<String, AsyncPlaceholder> async = new ConcurrentHashMap<>();
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();
    private static volatile VelociBoardPaper plugin;

    static void attach(VelociBoardPaper instance) {
        plugin = instance;
    }

    static void detach() {
        plugin = null;
        immediate.clear();
        for (AsyncPlaceholder placeholder : async.values()) {
            placeholder.values.clear();
        }
        async.clear();
        warned.clear();
    }

    /** Registers a cheap, player-thread-safe value read on the player's entity thread. */
    public static synchronized void registerPlaceholder(String name, Function<Player, String> resolver) {
        requirePlugin();
        checkName(name);
        checkAvailable(name);
        immediate.put(name, Objects.requireNonNull(resolver));
    }

    /** Runs the provider off-thread and sends its latest cached value. The provider receives only a UUID. */
    public static synchronized void registerAsyncPlaceholder(String name, Duration interval,
            Function<UUID, CompletionStage<String>> resolver) {
        requirePlugin();
        checkName(name);
        checkAvailable(name);
        if (interval == null || interval.compareTo(Duration.ofMillis(1)) < 0
                || interval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Refresh interval must be between 1 millisecond and 1 hour");
        }
        async.put(name, new AsyncPlaceholder(name, interval, Objects.requireNonNull(resolver)));
    }

    /** Removes a value registered by this API. Call this when the owning plugin disables. */
    public static synchronized void unregisterPlaceholder(String name) {
        immediate.remove(name);
        AsyncPlaceholder removed = async.remove(name);
        if (removed != null) {
            removed.values.clear();
        }
        warned.remove(name);
    }

    /** Schedules a fresh snapshot for a player after an event changes a registered value. */
    public static void refresh(Player player) {
        VelociBoardPaper current = requirePlugin();
        player.getScheduler().run(current, ignored -> current.sendSnapshot(player), () -> {});
    }

    static Map<String, String> snapshot(Player player) {
        Map<String, String> result = new HashMap<>();
        for (var entry : immediate.entrySet()) {
            try {
                result.put(entry.getKey(), clean(entry.getKey(), entry.getValue().apply(player)));
            } catch (RuntimeException error) {
                warn(entry.getKey(), "provider failed: " + error.getMessage());
            }
        }
        for (var entry : async.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get(player.getUniqueId()));
        }
        return result;
    }

    static void forget(UUID id) {
        for (AsyncPlaceholder placeholder : async.values()) {
            placeholder.values.remove(id);
        }
    }

    private static VelociBoardPaper requirePlugin() {
        VelociBoardPaper current = plugin;
        if (current == null) {
            throw new IllegalStateException("VelociBoardPaper is not enabled");
        }
        return current;
    }

    private static void checkName(String name) {
        if (name == null || !name.matches("[a-z][a-z0-9_]{0,31}")
                || Set.of("world", "x", "y", "z").contains(name)) {
            throw new IllegalArgumentException("Invalid or reserved bridge placeholder name");
        }
    }

    private static void checkAvailable(String name) {
        if (immediate.containsKey(name) || async.containsKey(name)) {
            throw new IllegalArgumentException("Bridge placeholder already registered: " + name);
        }
        if (immediate.size() + async.size() >= MAX_CUSTOM_VALUES) {
            throw new IllegalStateException("Bridge placeholder limit reached");
        }
    }

    private static String clean(String name, String value) {
        if (value == null) {
            return "";
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > 256 || value.chars().anyMatch(Character::isISOControl)) {
            warn(name, "value exceeds 256 bytes or contains a control character");
            return "";
        }
        return value;
    }

    private static void warn(String name, String issue) {
        VelociBoardPaper current = plugin;
        if (current != null && warned.add(name)) {
            current.getLogger().warning("Bridge placeholder '" + name + "': " + issue);
        }
    }

    private static final class AsyncPlaceholder {
        private final String name;
        private final Duration interval;
        private final Function<UUID, CompletionStage<String>> resolver;
        private final Map<UUID, CachedValue> values = new ConcurrentHashMap<>();

        private AsyncPlaceholder(String name, Duration interval, Function<UUID, CompletionStage<String>> resolver) {
            this.name = name;
            this.interval = interval;
            this.resolver = resolver;
        }

        private String get(UUID id) {
            CachedValue value = values.computeIfAbsent(id, ignored -> new CachedValue());
            synchronized (value) {
                if (!value.refreshing && System.nanoTime() >= value.refreshAt) {
                    value.refreshing = true;
                    VelociBoardPaper current = plugin;
                    if (current != null) {
                        current.getServer().getAsyncScheduler().runNow(current, ignored -> request(id, value));
                    }
                }
                return value.text;
            }
        }

        private void request(UUID id, CachedValue value) {
            try {
                resolver.apply(id).whenComplete((result, error) -> {
                    synchronized (value) {
                        if (values.get(id) != value) {
                            return;
                        }
                        value.refreshing = false;
                        value.refreshAt = System.nanoTime() + interval.toNanos();
                        if (error == null) {
                            value.text = clean(name, result);
                        } else {
                            warn(name, "provider failed: " + error.getMessage());
                        }
                    }
                });
            } catch (RuntimeException error) {
                synchronized (value) {
                    value.refreshing = false;
                    value.refreshAt = System.nanoTime() + interval.toNanos();
                }
                warn(name, "provider failed: " + error.getMessage());
            }
        }
    }

    private static final class CachedValue {
        private String text = "";
        private long refreshAt = Long.MIN_VALUE;
        private boolean refreshing;
    }

    private VelociBoardBridge() {
    }
}
