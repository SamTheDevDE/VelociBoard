package de.samthedev.velociboard.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VelociBoardBridgeTest {
    @AfterEach
    void cleanup() {
        VelociBoardBridge.detach();
    }

    @Test
    void registersAndRemovesCustomValues() {
        VelociBoardBridge.attach(mock(VelociBoardPaper.class, RETURNS_DEEP_STUBS));
        Player player = mock(Player.class);
        VelociBoardBridge.registerPlaceholder("economy_balance", ignored -> "42");
        assertEquals("42", VelociBoardBridge.snapshot(player).get("economy_balance"));
        assertThrows(IllegalArgumentException.class,
                () -> VelociBoardBridge.registerPlaceholder("economy_balance", ignored -> "43"));
        assertThrows(IllegalArgumentException.class,
                () -> VelociBoardBridge.registerPlaceholder("world", ignored -> "x"));
        VelociBoardBridge.unregisterPlaceholder("economy_balance");
        assertTrue(VelociBoardBridge.snapshot(player).isEmpty());
    }

    @Test
    void validatesAsyncRegistration() {
        VelociBoardPaper plugin = mock(VelociBoardPaper.class);
        Server server = mock(Server.class);
        AsyncScheduler scheduler = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(scheduler);
        AtomicReference<Consumer<ScheduledTask>> scheduled = new AtomicReference<>();
        doAnswer(invocation -> {
            scheduled.set(invocation.getArgument(1));
            return mock(ScheduledTask.class);
        }).when(scheduler).runNow(org.mockito.ArgumentMatchers.eq(plugin), org.mockito.ArgumentMatchers.any());
        VelociBoardBridge.attach(plugin);
        assertThrows(IllegalArgumentException.class,
                () -> VelociBoardBridge.registerAsyncPlaceholder("balance", Duration.ZERO,
                        ignored -> CompletableFuture.completedFuture("42")));
        AtomicInteger calls = new AtomicInteger();
        VelociBoardBridge.registerAsyncPlaceholder("balance", Duration.ofSeconds(2),
                ignored -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture("42");
                });
        Player player = mockPlayer();
        assertEquals("", VelociBoardBridge.snapshot(player).get("balance"));
        assertEquals(0, calls.get());
        scheduled.get().accept(mock(ScheduledTask.class));
        assertEquals("42", VelociBoardBridge.snapshot(player).get("balance"));
        assertEquals(1, calls.get());
    }

    private static Player mockPlayer() {
        Player player = mock(Player.class);
        org.mockito.Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }
}
