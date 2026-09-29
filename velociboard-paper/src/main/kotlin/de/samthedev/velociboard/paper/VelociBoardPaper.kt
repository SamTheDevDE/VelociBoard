package de.samthedev.velociboard.paper

import de.samthedev.velociboard.bridge.BridgeMessage
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class VelociBoardPaper : JavaPlugin(), Listener {
    private val lastSent = ConcurrentHashMap<UUID, Snapshot>()

    override fun onEnable() {
        VelociBoardBridge.attach(this)
        server.messenger.registerOutgoingPluginChannel(this, BridgeMessage.CHANNEL)
        server.pluginManager.registerEvents(this, this)
        server.globalRegionScheduler.runAtFixedRate(this, { _ ->
            for (player in server.onlinePlayers) {
                player.scheduler.run(this, { _ -> sendSnapshot(player) }, { lastSent.remove(player.uniqueId) })
            }
        }, 20L, 20L)
    }

    override fun onDisable() {
        VelociBoardBridge.detach()
        server.messenger.unregisterOutgoingPluginChannel(this, BridgeMessage.CHANNEL)
        lastSent.clear()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastSent.remove(event.player.uniqueId)
        VelociBoardBridge.forget(event.player.uniqueId)
    }

    internal fun sendSnapshot(player: Player) {
        if (!player.isOnline) return
        val location = player.location
        var world = location.world.name
        if (world.toByteArray(StandardCharsets.UTF_8).size > 256 || world.any(Character::isISOControl)) {
            world = "unknown"
        }
        val values = VelociBoardBridge.snapshot(player).toMutableMap()
        values["world"] = world
        values["x"] = location.blockX.toString()
        values["y"] = location.blockY.toString()
        values["z"] = location.blockZ.toString()
        val id = player.uniqueId
        val previous = lastSent[id]
        val now = System.nanoTime()
        if (previous == null || values != previous.values || now - previous.sentAt >= 10_000_000_000L) {
            player.sendPluginMessage(this, BridgeMessage.CHANNEL, BridgeMessage(id, values).encode())
            lastSent[id] = Snapshot(values, now)
        }
    }

    private data class Snapshot(val values: Map<String, String>, val sentAt: Long)
}
