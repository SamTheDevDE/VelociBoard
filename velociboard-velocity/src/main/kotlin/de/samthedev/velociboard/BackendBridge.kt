package de.samthedev.velociboard

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import de.samthedev.velociboard.bridge.BridgeMessage
import org.slf4j.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

class BackendBridge(private val proxy: ProxyServer, private val logger: Logger, private val onChange: Consumer<UUID>) {
    private val channel = MinecraftChannelIdentifier.from(BridgeMessage.CHANNEL)
    private val values = ConcurrentHashMap<UUID, State>()
    private val warnedServers = ConcurrentHashMap.newKeySet<String>()

    fun start(plugin: Any) {
        proxy.channelRegistrar.register(channel)
        proxy.eventManager.register(plugin, this)
    }

    fun stop(plugin: Any) {
        proxy.eventManager.unregisterListener(plugin, this)
        proxy.channelRegistrar.unregister(channel)
        values.clear()
    }

    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != channel) return
        event.result = PluginMessageEvent.ForwardResult.handled()
        val backend = event.source as? ServerConnection ?: return
        val player = backend.player
        val serverName = backend.server.serverInfo.name
        if (player.currentServer.map { it.server != backend.server }.orElse(true)) return
        val message = try {
            BridgeMessage.decode(event.data)
        } catch (error: IllegalArgumentException) {
            if (warnedServers.add(serverName)) {
                logger.warn("Ignoring malformed VelociBoard bridge message from {}: {}", serverName, error.message)
            }
            return
        }
        if (message.playerId != player.uniqueId) {
            if (warnedServers.add(serverName)) {
                logger.warn("Ignoring VelociBoard bridge message with wrong player UUID from {}", serverName)
            }
            return
        }
        val now = System.nanoTime()
        val old = values[player.uniqueId]
        if (old != null && now - old.updatedAt < MIN_INTERVAL) return
        values[player.uniqueId] = State(serverName, message.values, now)
        if (old == null || old.server != serverName || old.values != message.values) {
            onChange.accept(player.uniqueId)
        }
    }

    fun value(player: Player, key: String): String {
        val state = values[player.uniqueId] ?: return ""
        if (System.nanoTime() - state.updatedAt > STALE_AFTER) return ""
        val current = player.currentServer.map { it.server.serverInfo.name }.orElse("")
        return if (state.server == current) state.values[key].orEmpty() else ""
    }

    fun describe(player: Player): String {
        val state = values[player.uniqueId] ?: return "no data"
        val age = (System.nanoTime() - state.updatedAt) / 1_000_000
        return "${state.server}, ${state.values.size} values, ${age}ms old"
    }

    fun clearOnSwitch(player: Player) {
        val current = player.currentServer.map { it.server.serverInfo.name }.orElse("")
        values.computeIfPresent(player.uniqueId) { _, state -> if (state.server == current) state else null }
    }

    fun forget(player: Player) {
        values.remove(player.uniqueId)
    }

    fun expire() {
        val now = System.nanoTime()
        for ((id, state) in values) {
            if (now - state.updatedAt > STALE_AFTER && values.remove(id, state)) onChange.accept(id)
        }
    }

    private data class State(val server: String, val values: Map<String, String>, val updatedAt: Long)

    companion object {
        private const val STALE_AFTER = 20_000_000_000L
        private const val MIN_INTERVAL = 100_000_000L
    }
}
