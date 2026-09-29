package de.samthedev.velociboard

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaceholderRegistryTest {
    private val plain = PlainTextComponentSerializer.plainText()

    @Test
    fun replacesKnownTokensWithoutParsingTheirTextAsMiniMessage() {
        val player = Mockito.mock(Player::class.java)
        val placeholders = PlaceholderRegistry { }
        placeholders.register("example") { "<red>literal</red>" }
        val text = plain.serialize(placeholders.render(player, "<white>%example% %unknown% %example%"))
        assertEquals("<red>literal</red> %unknown% <red>literal</red>", text)
    }

    @Test
    fun cachedPlaceholderReturnsWithoutWaitingForItsValue() {
        val player = Mockito.mock(Player::class.java)
        val id = UUID.randomUUID()
        Mockito.`when`(player.uniqueId).thenReturn(id)
        val changed = CountDownLatch(1)
        val result = CompletableFuture<String>()
        val placeholders = PlaceholderRegistry { changed.countDown() }
        placeholders.registerCached("slow", Duration.ofSeconds(1)) { result }
        assertEquals("", plain.serialize(placeholders.render(player, "%slow%")))
        result.complete("ready")
        assertTrue(changed.await(2, TimeUnit.SECONDS))
        assertEquals("ready", plain.serialize(placeholders.render(player, "%slow%")))
        placeholders.forget(id)
    }

    @Test
    fun polledPlaceholderUsesCacheBetweenUpdates() {
        val player = Mockito.mock(Player::class.java)
        Mockito.`when`(player.uniqueId).thenReturn(UUID.randomUUID())
        val calls = AtomicInteger()
        val placeholders = PlaceholderRegistry { }
        placeholders.registerPolled("count", Duration.ofHours(1)) { calls.incrementAndGet().toString() }
        assertTrue(placeholders.update(player, false))
        assertEquals("1", plain.serialize(placeholders.render(player, "%count%")))
        assertFalse(placeholders.update(player, false))
        assertEquals("1", plain.serialize(placeholders.render(player, "%count%")))
        assertTrue(placeholders.update(player, true))
        assertEquals("2", plain.serialize(placeholders.render(player, "%count%")))
    }

    @Test
    fun repeatedPlaceholderResolvesOncePerRender() {
        val player = Mockito.mock(Player::class.java)
        val calls = AtomicInteger()
        val placeholders = PlaceholderRegistry { }
        placeholders.register("name") { calls.incrementAndGet().toString() }
        assertEquals("1 1", plain.serialize(placeholders.render(player, "%name% %name%", HashMap<String, Component>())))
        assertEquals(1, calls.get())
    }

    @Test
    fun resolvesBackendValuesUnderTheirOwnPrefix() {
        val player = Mockito.mock(Player::class.java)
        val placeholders = PlaceholderRegistry { }
        placeholders.setBackendResolver { _, name -> if (name == "economy_balance") "42" else "" }
        assertEquals("42", plain.serialize(placeholders.render(player, "%backend_economy_balance%")))
        assertEquals("%unknown%", plain.serialize(placeholders.render(player, "%unknown%")))
    }
}
