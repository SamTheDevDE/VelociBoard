package de.samthedev.velociboard

import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import de.samthedev.velociboard.bridge.BridgeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.Logger
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class BackendBridgeTest {
    @Test
    fun acceptsOnlyMessagesForTheConnectedPlayer() {
        val player = Mockito.mock(Player::class.java)
        val backend = Mockito.mock(ServerConnection::class.java)
        val server = Mockito.mock(RegisteredServer::class.java)
        val info = Mockito.mock(ServerInfo::class.java)
        val id = UUID.randomUUID()
        Mockito.`when`(player.uniqueId).thenReturn(id)
        Mockito.`when`(player.currentServer).thenReturn(Optional.of(backend))
        Mockito.`when`(backend.player).thenReturn(player)
        Mockito.`when`(backend.server).thenReturn(server)
        Mockito.`when`(server.serverInfo).thenReturn(info)
        Mockito.`when`(info.name).thenReturn("survival")
        val updates = AtomicInteger()
        val bridge = BackendBridge(Mockito.mock(ProxyServer::class.java), Mockito.mock(Logger::class.java)) {
            updates.incrementAndGet()
        }
        val channel = MinecraftChannelIdentifier.from(BridgeMessage.CHANNEL)

        val wrong = PluginMessageEvent(backend, player, channel,
            BridgeMessage(UUID.randomUUID(), mapOf("world" to "world")).encode())
        bridge.onPluginMessage(wrong)
        assertEquals("", bridge.value(player, "world"))
        assertEquals(0, updates.get())
        assertEquals(PluginMessageEvent.ForwardResult.handled(), wrong.result)

        val valid = PluginMessageEvent(backend, player, channel, BridgeMessage(id, mapOf("world" to "world")).encode())
        bridge.onPluginMessage(valid)
        assertEquals("world", bridge.value(player, "world"))
        assertEquals(1, updates.get())
    }
}
