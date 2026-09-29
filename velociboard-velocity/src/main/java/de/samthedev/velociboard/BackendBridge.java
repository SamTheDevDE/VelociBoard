package de.samthedev.velociboard;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import de.samthedev.velociboard.bridge.BridgeMessage;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;

final class BackendBridge {
    private static final long STALE_AFTER = 20_000_000_000L;
    private static final long MIN_INTERVAL = 100_000_000L;
    private final MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(BridgeMessage.CHANNEL);
    private final Map<UUID, State> values = new ConcurrentHashMap<>();
    private final Set<String> warnedServers = ConcurrentHashMap.newKeySet();
    private final ProxyServer proxy;
    private final Logger logger;
    private final Consumer<UUID> onChange;

    BackendBridge(ProxyServer proxy, Logger logger, Consumer<UUID> onChange) {
        this.proxy = proxy;
        this.logger = logger;
        this.onChange = onChange;
    }

    void start(Object plugin) {
        proxy.getChannelRegistrar().register(channel);
        proxy.getEventManager().register(plugin, this);
    }

    void stop(Object plugin) {
        proxy.getEventManager().unregisterListener(plugin, this);
        proxy.getChannelRegistrar().unregister(channel);
        values.clear();
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!channel.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection backend)) {
            return;
        }
        Player player = backend.getPlayer();
        String serverName = backend.getServer().getServerInfo().getName();
        if (player.getCurrentServer().map(connection -> !connection.getServer().equals(backend.getServer()))
                .orElse(true)) {
            return;
        }
        BridgeMessage message;
        try {
            message = BridgeMessage.decode(event.getData());
        } catch (IllegalArgumentException error) {
            if (warnedServers.add(serverName)) {
                logger.warn("Ignoring malformed VelociBoard bridge message from {}: {}", serverName, error.getMessage());
            }
            return;
        }
        if (!message.playerId().equals(player.getUniqueId())) {
            if (warnedServers.add(serverName)) {
                logger.warn("Ignoring VelociBoard bridge message with wrong player UUID from {}", serverName);
            }
            return;
        }
        long now = System.nanoTime();
        State old = values.get(player.getUniqueId());
        if (old != null && now - old.updatedAt() < MIN_INTERVAL) {
            return;
        }
        values.put(player.getUniqueId(), new State(serverName, message.values(), now));
        if (old == null || !old.server().equals(serverName) || !old.values().equals(message.values())) {
            onChange.accept(player.getUniqueId());
        }
    }

    String value(Player player, String key) {
        State state = values.get(player.getUniqueId());
        if (state == null || System.nanoTime() - state.updatedAt() > STALE_AFTER) {
            return "";
        }
        String current = player.getCurrentServer()
                .map(connection -> connection.getServer().getServerInfo().getName()).orElse("");
        return state.server().equals(current) ? state.values().getOrDefault(key, "") : "";
    }

    void clearOnSwitch(Player player) {
        String current = player.getCurrentServer()
                .map(connection -> connection.getServer().getServerInfo().getName()).orElse("");
        values.computeIfPresent(player.getUniqueId(), (id, state) -> state.server().equals(current) ? state : null);
    }

    void forget(Player player) {
        values.remove(player.getUniqueId());
    }

    void expire() {
        long now = System.nanoTime();
        for (var entry : values.entrySet()) {
            if (now - entry.getValue().updatedAt() > STALE_AFTER
                    && values.remove(entry.getKey(), entry.getValue())) {
                onChange.accept(entry.getKey());
            }
        }
    }

    private record State(String server, Map<String, String> values, long updatedAt) {
    }
}
