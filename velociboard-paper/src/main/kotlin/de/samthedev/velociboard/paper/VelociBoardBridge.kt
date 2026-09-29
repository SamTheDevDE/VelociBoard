package de.samthedev.velociboard.paper

import org.bukkit.entity.Player
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

object VelociBoardBridge {
    private const val MAX_CUSTOM_VALUES = 12
    private val immediate = ConcurrentHashMap<String, Function<Player, String>>()
    private val async = ConcurrentHashMap<String, AsyncPlaceholder>()
    private val warned = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var plugin: VelociBoardPaper? = null

    internal fun attach(instance: VelociBoardPaper) {
        plugin = instance
    }

    internal fun detach() {
        plugin = null
        immediate.clear()
        async.values.forEach { it.values.clear() }
        async.clear()
        warned.clear()
    }

    /** Reads a cheap local value on the player's entity thread. */
    @JvmStatic
    @Synchronized
    fun registerPlaceholder(name: String, resolver: Function<Player, String>) {
        requirePlugin()
        checkName(name)
        checkAvailable(name)
        immediate[name] = resolver
    }

    /** Runs the provider off-thread and sends its latest cached value. */
    @JvmStatic
    @Synchronized
    fun registerAsyncPlaceholder(name: String, interval: Duration, resolver: Function<UUID, CompletionStage<String>>) {
        requirePlugin()
        checkName(name)
        checkAvailable(name)
        require(interval >= Duration.ofMillis(1) && interval <= Duration.ofHours(1)) {
            "Refresh interval must be between 1 millisecond and 1 hour"
        }
        async[name] = AsyncPlaceholder(name, interval, resolver)
    }

    /** Call when the owning plugin disables. */
    @JvmStatic
    @Synchronized
    fun unregisterPlaceholder(name: String) {
        immediate.remove(name)
        async.remove(name)?.values?.clear()
        warned.remove(name)
    }

    /** Schedules a fresh snapshot after an event changes a registered value. */
    @JvmStatic
    fun refresh(player: Player) {
        val current = requirePlugin()
        player.scheduler.run(current, { _ -> current.sendSnapshot(player) }, {})
    }

    internal fun snapshot(player: Player): Map<String, String> {
        val result = HashMap<String, String>()
        for ((name, resolver) in immediate) {
            try {
                result[name] = clean(name, resolver.apply(player))
            } catch (error: RuntimeException) {
                warn(name, "provider failed: ${error.message}")
            }
        }
        for ((name, placeholder) in async) {
            result[name] = placeholder.get(player.uniqueId)
        }
        return result
    }

    internal fun forget(id: UUID) {
        async.values.forEach { it.values.remove(id) }
    }

    private fun requirePlugin(): VelociBoardPaper = plugin ?: error("VelociBoardPaper is not enabled")

    private fun checkName(name: String) {
        require(name.matches(Regex("[a-z][a-z0-9_]{0,31}")) && name !in setOf("world", "x", "y", "z")) {
            "Invalid or reserved bridge placeholder name"
        }
    }

    private fun checkAvailable(name: String) {
        require(!immediate.containsKey(name) && !async.containsKey(name)) {
            "Bridge placeholder already registered: $name"
        }
        check(immediate.size + async.size < MAX_CUSTOM_VALUES) { "Bridge placeholder limit reached" }
    }

    private fun clean(name: String, value: String?): String {
        if (value == null) return ""
        if (value.toByteArray(StandardCharsets.UTF_8).size > 256 || value.any(Character::isISOControl)) {
            warn(name, "value exceeds 256 bytes or contains a control character")
            return ""
        }
        return value
    }

    private fun warn(name: String, issue: String) {
        val current = plugin
        if (current != null && warned.add(name)) {
            current.logger.warning("Bridge placeholder '$name': $issue")
        }
    }

    private class AsyncPlaceholder(
        private val name: String,
        private val interval: Duration,
        private val resolver: Function<UUID, CompletionStage<String>>,
    ) {
        val values = ConcurrentHashMap<UUID, CachedValue>()

        fun get(id: UUID): String {
            val value = values.computeIfAbsent(id) { CachedValue() }
            synchronized(value) {
                if (!value.refreshing && System.nanoTime() >= value.refreshAt) {
                    value.refreshing = true
                    val current = plugin
                    if (current != null) {
                        current.server.asyncScheduler.runNow(current) { _ -> request(id, value) }
                    } else {
                        value.refreshing = false
                    }
                }
                return value.text
            }
        }

        private fun request(id: UUID, value: CachedValue) {
            try {
                resolver.apply(id).whenComplete { result, error ->
                    synchronized(value) {
                        if (values[id] != value) return@whenComplete
                        value.refreshing = false
                        value.refreshAt = System.nanoTime() + interval.toNanos()
                        if (error == null) {
                            value.text = clean(name, result)
                        } else {
                            warn(name, "provider failed: ${error.message}")
                        }
                    }
                }
            } catch (error: RuntimeException) {
                synchronized(value) {
                    value.refreshing = false
                    value.refreshAt = System.nanoTime() + interval.toNanos()
                }
                warn(name, "provider failed: ${error.message}")
            }
        }
    }

    private class CachedValue {
        var text = ""
        var refreshAt = Long.MIN_VALUE
        var refreshing = false
    }
}
