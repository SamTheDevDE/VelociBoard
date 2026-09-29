package de.samthedev.velociboard.paper

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Server
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

class VelociBoardBridgeTest {
    @AfterEach
    fun cleanup() {
        VelociBoardBridge.detach()
    }

    @Test
    fun registersAndRemovesCustomValues() {
        VelociBoardBridge.attach(Mockito.mock(VelociBoardPaper::class.java, RETURNS_DEEP_STUBS))
        val player = Mockito.mock(Player::class.java)
        VelociBoardBridge.registerPlaceholder("economy_balance") { "42" }
        assertEquals("42", VelociBoardBridge.snapshot(player)["economy_balance"])
        assertThrows(IllegalArgumentException::class.java) {
            VelociBoardBridge.registerPlaceholder("economy_balance") { "43" }
        }
        assertThrows(IllegalArgumentException::class.java) {
            VelociBoardBridge.registerPlaceholder("world") { "x" }
        }
        VelociBoardBridge.unregisterPlaceholder("economy_balance")
        assertTrue(VelociBoardBridge.snapshot(player).isEmpty())
    }

    @Test
    fun validatesAsyncRegistration() {
        val plugin = Mockito.mock(VelociBoardPaper::class.java)
        val server = Mockito.mock(Server::class.java)
        val scheduler = Mockito.mock(AsyncScheduler::class.java)
        Mockito.`when`(plugin.server).thenReturn(server)
        Mockito.`when`(server.asyncScheduler).thenReturn(scheduler)
        Mockito.doAnswer { Mockito.mock(ScheduledTask::class.java) }
            .`when`(scheduler).runNow(Mockito.eq(plugin), Mockito.any())
        VelociBoardBridge.attach(plugin)
        assertThrows(IllegalArgumentException::class.java) {
            VelociBoardBridge.registerAsyncPlaceholder("balance", Duration.ZERO) {
                CompletableFuture.completedFuture("42")
            }
        }
        val calls = AtomicInteger()
        VelociBoardBridge.registerAsyncPlaceholder("balance", Duration.ofSeconds(2)) {
            calls.incrementAndGet()
            CompletableFuture.completedFuture("42")
        }
        val player = Mockito.mock(Player::class.java)
        Mockito.`when`(player.uniqueId).thenReturn(UUID.randomUUID())
        assertEquals("", VelociBoardBridge.snapshot(player)["balance"])
        assertEquals(0, calls.get())
        @Suppress("UNCHECKED_CAST")
        val scheduled = ArgumentCaptor.forClass(Consumer::class.java) as ArgumentCaptor<Consumer<ScheduledTask>>
        Mockito.verify(scheduler).runNow(Mockito.eq(plugin), scheduled.capture())
        scheduled.value.accept(Mockito.mock(ScheduledTask::class.java))
        assertEquals("42", VelociBoardBridge.snapshot(player)["balance"])
        assertEquals(1, calls.get())
    }
}
