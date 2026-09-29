package de.samthedev.velociboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import de.samthedev.velociboard.bridge.BridgeMessage;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class BackendBridgeTest {
    @Test
    void acceptsOnlyMessagesForTheConnectedPlayer() {
        Player player = mock(Player.class);
        ServerConnection backend = mock(ServerConnection.class);
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getCurrentServer()).thenReturn(Optional.of(backend));
        when(backend.getPlayer()).thenReturn(player);
        when(backend.getServer()).thenReturn(server);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn("survival");
        AtomicInteger updates = new AtomicInteger();
        BackendBridge bridge = new BackendBridge(mock(ProxyServer.class), mock(Logger.class), ignored -> updates.incrementAndGet());
        MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.from(BridgeMessage.CHANNEL);

        PluginMessageEvent wrong = new PluginMessageEvent(backend, player, channel,
                new BridgeMessage(UUID.randomUUID(), Map.of("world", "world")).encode());
        bridge.onPluginMessage(wrong);
        assertEquals("", bridge.value(player, "world"));
        assertEquals(0, updates.get());
        assertEquals(PluginMessageEvent.ForwardResult.handled(), wrong.getResult());

        PluginMessageEvent valid = new PluginMessageEvent(backend, player, channel,
                new BridgeMessage(id, Map.of("world", "world")).encode());
        bridge.onPluginMessage(valid);
        assertEquals("world", bridge.value(player, "world"));
        assertEquals(1, updates.get());
    }
}
