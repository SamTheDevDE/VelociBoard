package de.samthedev.velociboard

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.Function
import java.util.regex.Matcher
import java.util.regex.Pattern

internal class PlaceholderRegistry(private val onChange: Consumer<UUID>) {
    private val miniMessage = MiniMessage.miniMessage()
    private val immediate = HashMap<String, Function<Player, Component>>()
    private val cached = HashMap<String, CachedPlaceholder>()
    private val polled = HashMap<String, PolledPlaceholder>()
    private var backendResolver: BiFunction<Player, String, String>? = null

    @Synchronized
    fun setBackendResolver(resolver: BiFunction<Player, String, String>) {
        backendResolver = resolver
    }

    @Synchronized
    fun register(name: String, resolver: Function<Player, String>) {
        registerComponent(name) { player -> Component.text(resolver.apply(player) ?: "") }
    }

    @Synchronized
    fun registerComponent(name: String, resolver: Function<Player, Component>) {
        checkName(name)
        require(!contains(name)) { "Placeholder already registered: $name" }
        immediate[name] = resolver
    }

    @Synchronized
    fun registerCached(name: String, refreshInterval: Duration, resolver: Function<Player, CompletionStage<String>>) {
        checkName(name)
        require(!refreshInterval.isNegative && !refreshInterval.isZero) { "Refresh interval must be positive" }
        require(!contains(name)) { "Placeholder already registered: $name" }
        cached[name] = CachedPlaceholder(refreshInterval, resolver)
    }

    @Synchronized
    fun unregister(name: String) {
        immediate.remove(name)
        cached.remove(name)
        polled.remove(name)
    }

    @Synchronized
    fun registerPolled(name: String, interval: Duration, resolver: Function<Player, String>) {
        registerPolledComponent(name, interval) { player -> Component.text(resolver.apply(player) ?: "") }
    }

    @Synchronized
    fun registerPolledComponent(name: String, interval: Duration, resolver: Function<Player, Component>) {
        checkName(name)
        require(!interval.isZero && !interval.isNegative) { "Refresh interval must be positive" }
        require(!contains(name)) { "Placeholder already registered: $name" }
        polled[name] = PolledPlaceholder(interval, resolver)
    }

    @Synchronized
    fun configureRefresh(intervals: Map<String, Duration>) {
        for ((name, interval) in intervals) polled[name]?.interval = interval
    }

    @Synchronized
    fun update(player: Player, force: Boolean): Boolean {
        val now = System.nanoTime()
        var changed = false
        for (placeholder in polled.values) changed = placeholder.update(player, now, force) || changed
        return changed
    }

    @Synchronized
    fun render(player: Player, template: String): Component = render(player, template, HashMap())

    @Synchronized
    fun render(player: Player, template: String, resolved: MutableMap<String, Component>): Component {
        val matcher = TOKEN.matcher(template)
        val result = StringBuilder()
        val tags = TagResolver.builder()
        var index = 0
        while (matcher.find()) {
            val name = matcher.group(1)
            val value = resolve(player, name, resolved) ?: continue
            val tag = "vb_value_${index++}"
            tags.resolver(Placeholder.component(tag, value))
            matcher.appendReplacement(result, Matcher.quoteReplacement("<$tag>"))
        }
        matcher.appendTail(result)
        return miniMessage.deserialize(result.toString(), tags.build())
    }

    @Synchronized
    fun resolveText(player: Player, name: String, resolved: MutableMap<String, Component>): String? {
        val value = resolve(player, name, resolved) ?: return null
        return PlainTextComponentSerializer.plainText().serialize(value)
    }

    private fun resolve(player: Player, name: String, resolved: MutableMap<String, Component>): Component? {
        if (!contains(name)) return null
        return resolved.getOrPut(name) {
            when {
                immediate.containsKey(name) -> immediate.getValue(name).apply(player) ?: Component.empty()
                cached.containsKey(name) -> Component.text(cached.getValue(name).get(player))
                polled.containsKey(name) -> polled.getValue(name).get(player.uniqueId)
                else -> Component.text(backendResolver?.apply(player, name.substring(8)) ?: "")
            }
        }
    }

    @Synchronized
    fun forget(playerId: UUID) {
        cached.values.forEach { it.forget(playerId) }
        polled.values.forEach { it.forget(playerId) }
    }

    @Synchronized
    fun names(): Set<String> = sortedSetOf<String>().also {
        it.addAll(immediate.keys)
        it.addAll(polled.keys)
        it.addAll(cached.keys)
    }

    @Synchronized
    fun debugValue(player: Player, name: String): String {
        val value = resolveText(player, name, HashMap()) ?: return "unknown"
        val refreshedAt = polled[name]?.refreshedAt(player.uniqueId)
            ?: cached[name]?.refreshedAt(player.uniqueId) ?: 0
        val age = if (refreshedAt == 0L) "event"
            else "${Duration.ofNanos(System.nanoTime() - refreshedAt).toMillis()}ms ago"
        return "$value ($age)"
    }

    private fun contains(name: String) = immediate.containsKey(name) || cached.containsKey(name)
        || polled.containsKey(name) || (backendResolver != null && name.startsWith("backend_") && name.length > 8)

    private fun checkName(name: String) {
        require(name.matches(Regex("[a-z][a-z0-9_]*"))) {
            "Placeholder name must use lowercase letters, numbers and underscores"
        }
    }

    private inner class CachedPlaceholder(
        private val interval: Duration,
        private val resolver: Function<Player, CompletionStage<String>>,
    ) {
        private val values = HashMap<UUID, CachedValue>()

        fun get(player: Player): String {
            val id = player.uniqueId
            val value = values.getOrPut(id) { CachedValue() }
            if (!value.refreshing && System.nanoTime() >= value.refreshAt) {
                value.refreshing = true
                CompletableFuture.supplyAsync { resolver.apply(player) }
                    .thenCompose { it }
                    .whenComplete { result, error -> complete(id, value, result, error) }
            }
            return value.text
        }

        private fun complete(id: UUID, value: CachedValue, result: String?, error: Throwable?) {
            synchronized(this@PlaceholderRegistry) {
                if (values[id] != value) return
                value.refreshing = false
                value.refreshAt = System.nanoTime() + interval.toNanos()
                if (error != null) return
                value.text = result ?: ""
                value.refreshedAt = System.nanoTime()
            }
            onChange.accept(id)
        }

        fun forget(id: UUID) {
            values.remove(id)
        }

        fun refreshedAt(id: UUID) = values[id]?.refreshedAt ?: 0
    }

    private class PolledPlaceholder(var interval: Duration, private val resolver: Function<Player, Component>) {
        private val values = HashMap<UUID, PolledValue>()

        fun update(player: Player, now: Long, force: Boolean): Boolean {
            val id = player.uniqueId
            val previous = values[id]
            if (!force && previous != null && now < previous.nextRefresh) return false
            val value = resolver.apply(player) ?: Component.empty()
            values[id] = PolledValue(value, now, now + interval.toNanos())
            return previous == null || value != previous.text
        }

        fun get(id: UUID): Component = values[id]?.text ?: Component.empty()

        fun forget(id: UUID) {
            values.remove(id)
        }

        fun refreshedAt(id: UUID) = values[id]?.refreshedAt ?: 0
    }

    private data class PolledValue(val text: Component, val refreshedAt: Long, val nextRefresh: Long)

    private class CachedValue {
        var text = ""
        var refreshAt = Long.MIN_VALUE
        var refreshedAt = 0L
        var refreshing = false
    }

    companion object {
        private val TOKEN = Pattern.compile("%([a-z][a-z0-9_]*)%")
    }
}
