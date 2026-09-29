package de.samthedev.velociboard;

import com.velocitypowered.api.proxy.Player;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class PlaceholderRegistry {
    private static final Pattern TOKEN = Pattern.compile("%([a-z][a-z0-9_]*)%");
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, Function<Player, Component>> immediate = new HashMap<>();
    private final Map<String, CachedPlaceholder> cached = new HashMap<>();
    private final Map<String, PolledPlaceholder> polled = new HashMap<>();
    private final Consumer<UUID> onChange;

    PlaceholderRegistry(Consumer<UUID> onChange) {
        this.onChange = onChange;
    }

    public synchronized void register(String name, Function<Player, String> resolver) {
        Objects.requireNonNull(resolver);
        registerComponent(name, player -> Component.text(Objects.toString(resolver.apply(player), "")));
    }

    public synchronized void registerComponent(String name, Function<Player, Component> resolver) {
        checkName(name);
        if (contains(name)) {
            throw new IllegalArgumentException("Placeholder already registered: " + name);
        }
        immediate.put(name, Objects.requireNonNull(resolver));
    }

    public synchronized void registerCached(String name, Duration refreshInterval,
            Function<Player, CompletionStage<String>> resolver) {
        checkName(name);
        if (refreshInterval.isNegative() || refreshInterval.isZero()) {
            throw new IllegalArgumentException("Refresh interval must be positive");
        }
        if (contains(name)) {
            throw new IllegalArgumentException("Placeholder already registered: " + name);
        }
        cached.put(name, new CachedPlaceholder(refreshInterval, Objects.requireNonNull(resolver)));
    }

    synchronized void registerPolled(String name, Duration interval, Function<Player, String> resolver) {
        Objects.requireNonNull(resolver);
        registerPolledComponent(name, interval,
                player -> Component.text(Objects.toString(resolver.apply(player), "")));
    }

    synchronized void registerPolledComponent(String name, Duration interval, Function<Player, Component> resolver) {
        checkName(name);
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Refresh interval must be positive");
        }
        if (contains(name)) {
            throw new IllegalArgumentException("Placeholder already registered: " + name);
        }
        polled.put(name, new PolledPlaceholder(interval, Objects.requireNonNull(resolver)));
    }

    synchronized void configureRefresh(Map<String, Duration> intervals) {
        for (var entry : intervals.entrySet()) {
            PolledPlaceholder placeholder = polled.get(entry.getKey());
            if (placeholder != null) {
                placeholder.interval = entry.getValue();
            }
        }
    }

    synchronized boolean update(Player player, boolean force) {
        boolean changed = false;
        long now = System.nanoTime();
        for (PolledPlaceholder placeholder : polled.values()) {
            changed |= placeholder.update(player, now, force);
        }
        return changed;
    }

    synchronized Component render(Player player, String template) {
        return render(player, template, new HashMap<>());
    }

    synchronized Component render(Player player, String template, Map<String, Component> resolved) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder result = new StringBuilder();
        TagResolver.Builder tags = TagResolver.builder();
        int index = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            Component value = resolve(player, name, resolved);
            if (value == null) {
                continue;
            }
            String tag = "vb_value_" + index++;
            tags.resolver(Placeholder.component(tag, value));
            matcher.appendReplacement(result, Matcher.quoteReplacement("<" + tag + ">"));
        }
        matcher.appendTail(result);
        return miniMessage.deserialize(result.toString(), tags.build());
    }

    synchronized String resolveText(Player player, String name, Map<String, Component> resolved) {
        Component value = resolve(player, name, resolved);
        return value == null ? null : PlainTextComponentSerializer.plainText().serialize(value);
    }

    private Component resolve(Player player, String name, Map<String, Component> resolved) {
        if (!contains(name)) {
            return null;
        }
        return resolved.computeIfAbsent(name, ignored -> {
            if (immediate.containsKey(name)) {
                return Objects.requireNonNullElse(immediate.get(name).apply(player), Component.empty());
            }
            if (cached.containsKey(name)) {
                return Component.text(cached.get(name).get(player));
            }
            return polled.get(name).get(player.getUniqueId());
        });
    }

    synchronized void forget(UUID playerId) {
        for (CachedPlaceholder placeholder : cached.values()) {
            placeholder.forget(playerId);
        }
        for (PolledPlaceholder placeholder : polled.values()) {
            placeholder.forget(playerId);
        }
    }

    private boolean contains(String name) {
        return immediate.containsKey(name) || cached.containsKey(name) || polled.containsKey(name);
    }

    private static void checkName(String name) {
        if (name == null || !name.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Placeholder name must use lowercase letters, numbers and underscores");
        }
    }

    private final class CachedPlaceholder {
        private final Duration interval;
        private final Function<Player, CompletionStage<String>> resolver;
        private final Map<UUID, CachedValue> values = new HashMap<>();

        private CachedPlaceholder(Duration interval, Function<Player, CompletionStage<String>> resolver) {
            this.interval = interval;
            this.resolver = resolver;
        }

        private String get(Player player) {
            UUID id = player.getUniqueId();
            CachedValue value = values.computeIfAbsent(id, ignored -> new CachedValue());
            if (!value.refreshing && System.nanoTime() >= value.refreshAt) {
                value.refreshing = true;
                CompletableFuture.supplyAsync(() -> resolver.apply(player))
                        .thenCompose(Function.identity())
                        .whenComplete((resolved, error) -> complete(id, value, resolved, error));
            }
            return value.text;
        }

        private void complete(UUID id, CachedValue value, String resolved, Throwable error) {
            synchronized (PlaceholderRegistry.this) {
                if (values.get(id) != value) {
                    return;
                }
                value.refreshing = false;
                value.refreshAt = System.nanoTime() + interval.toNanos();
                if (error != null) {
                    return;
                }
                value.text = resolved == null ? "" : resolved;
            }
            onChange.accept(id);
        }

        private void forget(UUID id) {
            values.remove(id);
        }
    }

    private static final class PolledPlaceholder {
        private Duration interval;
        private final Function<Player, Component> resolver;
        private final Map<UUID, PolledValue> values = new HashMap<>();

        private PolledPlaceholder(Duration interval, Function<Player, Component> resolver) {
            this.interval = interval;
            this.resolver = resolver;
        }

        private boolean update(Player player, long now, boolean force) {
            UUID id = player.getUniqueId();
            PolledValue previous = values.get(id);
            if (!force && previous != null && now < previous.nextRefresh) {
                return false;
            }
            Component value = Objects.requireNonNullElse(resolver.apply(player), Component.empty());
            values.put(id, new PolledValue(value, now + interval.toNanos()));
            return previous == null || !value.equals(previous.text);
        }

        private Component get(UUID id) {
            PolledValue value = values.get(id);
            return value == null ? Component.empty() : value.text;
        }

        private void forget(UUID id) {
            values.remove(id);
        }
    }

    private record PolledValue(Component text, long nextRefresh) {
    }

    private static final class CachedValue {
        private String text = "";
        private long refreshAt = Long.MIN_VALUE;
        private boolean refreshing;
    }
}
