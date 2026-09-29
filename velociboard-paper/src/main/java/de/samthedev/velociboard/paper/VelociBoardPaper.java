package de.samthedev.velociboard.paper;

import de.samthedev.velociboard.bridge.BridgeMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class VelociBoardPaper extends JavaPlugin implements Listener {
    private final Map<UUID, Snapshot> lastSent = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        VelociBoardBridge.attach(this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeMessage.CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            for (Player player : getServer().getOnlinePlayers()) {
                player.getScheduler().run(this, ignored -> sendSnapshot(player),
                        () -> lastSent.remove(player.getUniqueId()));
            }
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        VelociBoardBridge.detach();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, BridgeMessage.CHANNEL);
        lastSent.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastSent.remove(event.getPlayer().getUniqueId());
        VelociBoardBridge.forget(event.getPlayer().getUniqueId());
    }

    void sendSnapshot(Player player) {
        if (!player.isOnline()) {
            return;
        }
        Location location = player.getLocation();
        String world = location.getWorld().getName();
        if (world.getBytes(StandardCharsets.UTF_8).length > 256
                || world.chars().anyMatch(Character::isISOControl)) {
            world = "unknown";
        }
        Map<String, String> values = new HashMap<>(VelociBoardBridge.snapshot(player));
        values.put("world", world);
        values.put("x", Integer.toString(location.getBlockX()));
        values.put("y", Integer.toString(location.getBlockY()));
        values.put("z", Integer.toString(location.getBlockZ()));
        UUID id = player.getUniqueId();
        Snapshot previous = lastSent.get(id);
        long now = System.nanoTime();
        if (previous == null || !values.equals(previous.values()) || now - previous.sentAt() >= 10_000_000_000L) {
            byte[] message = new BridgeMessage(id, values).encode();
            player.sendPluginMessage(this, BridgeMessage.CHANNEL, message);
            lastSent.put(id, new Snapshot(values, now));
        }
    }

    private record Snapshot(Map<String, String> values, long sentAt) {
    }
}
