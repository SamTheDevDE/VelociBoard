package de.samthedev.velociboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.Player;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class PlaceholderRegistryTest {
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    @Test
    void replacesKnownTokensWithoutParsingTheirTextAsMiniMessage() {
        Player player = mock(Player.class);
        PlaceholderRegistry placeholders = new PlaceholderRegistry(ignored -> {});
        placeholders.register("example", ignored -> "<red>literal</red>");

        String text = plain.serialize(placeholders.render(player, "<white>%example% %unknown% %example%"));
        assertEquals("<red>literal</red> %unknown% <red>literal</red>", text);
    }

    @Test
    void cachedPlaceholderReturnsWithoutWaitingForItsValue() throws Exception {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        CountDownLatch changed = new CountDownLatch(1);
        CompletableFuture<String> result = new CompletableFuture<>();
        PlaceholderRegistry placeholders = new PlaceholderRegistry(ignored -> changed.countDown());
        placeholders.registerCached("slow", Duration.ofSeconds(1), ignored -> result);

        assertEquals("", plain.serialize(placeholders.render(player, "%slow%")));
        result.complete("ready");
        assertTrue(changed.await(2, TimeUnit.SECONDS));
        assertEquals("ready", plain.serialize(placeholders.render(player, "%slow%")));
        placeholders.forget(id);
    }

    @Test
    void polledPlaceholderUsesCacheBetweenUpdates() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        AtomicInteger calls = new AtomicInteger();
        PlaceholderRegistry placeholders = new PlaceholderRegistry(ignored -> {});
        placeholders.registerPolled("count", Duration.ofHours(1), ignored -> Integer.toString(calls.incrementAndGet()));

        assertTrue(placeholders.update(player, false));
        assertEquals("1", plain.serialize(placeholders.render(player, "%count%")));
        assertFalse(placeholders.update(player, false));
        assertEquals("1", plain.serialize(placeholders.render(player, "%count%")));
        assertTrue(placeholders.update(player, true));
        assertEquals("2", plain.serialize(placeholders.render(player, "%count%")));
    }

    @Test
    void repeatedPlaceholderResolvesOncePerRender() {
        Player player = mock(Player.class);
        AtomicInteger calls = new AtomicInteger();
        PlaceholderRegistry placeholders = new PlaceholderRegistry(ignored -> {});
        placeholders.register("name", ignored -> Integer.toString(calls.incrementAndGet()));

        assertEquals("1 1", plain.serialize(placeholders.render(player, "%name% %name%", new HashMap<>())));
        assertEquals(1, calls.get());
    }

    @Test
    void resolvesBackendValuesUnderTheirOwnPrefix() {
        Player player = mock(Player.class);
        PlaceholderRegistry placeholders = new PlaceholderRegistry(ignored -> {});
        placeholders.setBackendResolver((ignored, name) -> name.equals("economy_balance") ? "42" : "");

        assertEquals("42", plain.serialize(placeholders.render(player, "%backend_economy_balance%")));
        assertEquals("%unknown%", plain.serialize(placeholders.render(player, "%unknown%")));
    }
}
