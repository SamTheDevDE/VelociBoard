package de.samthedev.velociboard

import com.google.inject.Inject
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.scheduler.ScheduledTask
import de.samthedev.velociboard.api.VelociBoardAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.slf4j.Logger
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function

class VelociBoard @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    @Volatile private var config: BoardConfig? = null
    private val placeholders = PlaceholderRegistry(::refreshPlayer)
    private val renderer = SidebarRenderer(placeholders, ::wantsBoard)
    private val bridge = BackendBridge(proxy, logger, ::refreshPlayer)
    private val preferences = PreferenceStore(dataDirectory.resolve("preferences.db"), logger)
    private val publicApi: VelociBoardAPI = PluginApi()
    private val preferenceStates = ConcurrentHashMap<UUID, PreferenceState>()
    private val previews = ConcurrentHashMap<UUID, String>()
    private var refreshTask: ScheduledTask? = null
    private var nextConditionCheck = Long.MIN_VALUE
    @Volatile private var stopping = false

    init {
        placeholders.setBackendResolver { player, key -> bridge.value(player, key) }
        placeholders.register("player_name") { it.username }
        placeholders.register("player_uuid") { it.uniqueId.toString() }
        placeholders.register("server_name") { player ->
            player.currentServer.map { it.server.serverInfo.name }.orElse("unknown")
        }
        placeholders.registerPolled("server_online", Duration.ofSeconds(1)) { player ->
            player.currentServer.map { it.server.playersConnected.size.toString() }.orElse("0")
        }
        placeholders.registerPolled("network_online", Duration.ofSeconds(1)) { proxy.playerCount.toString() }
        placeholders.registerPolled("ping", Duration.ofSeconds(5)) { it.ping.toString() }
    }

    @Subscribe
    fun onProxyInitialize(event: ProxyInitializeEvent) {
        preferences.start()
        bridge.start(this)
        if (proxy.pluginManager.getPlugin("luckperms").isPresent) {
            try {
                LuckPermsPlaceholders.install(placeholders)
            } catch (error: IllegalStateException) {
                logger.warn("LuckPerms placeholders unavailable: {}", error.message)
                registerEmptyLuckPermsPlaceholders()
            } catch (error: LinkageError) {
                logger.warn("LuckPerms placeholders unavailable: {}", error.message)
                registerEmptyLuckPermsPlaceholders()
            }
        } else {
            registerEmptyLuckPermsPlaceholders()
        }
        reload()
        val meta = proxy.commandManager.metaBuilder("velociboard").aliases("vboard", "vb").plugin(this).build()
        proxy.commandManager.register(meta, BoardCommand())
        val scoreboard = proxy.commandManager.metaBuilder("scoreboard").plugin(this).build()
        proxy.commandManager.register(scoreboard, SimpleCommand { invocation -> toggle(invocation.source()) })
        refreshTask = proxy.scheduler.buildTask(this, Runnable {
            val current = config
            val animated = current?.animations?.tick() ?: false
            var conditionsDue = false
            val now = System.nanoTime()
            if (now >= nextConditionCheck) {
                nextConditionCheck = now + Duration.ofSeconds(1).toNanos()
                conditionsDue = current?.hasConditions() ?: false
                bridge.expire()
            }
            for (player in proxy.allPlayers) {
                if (placeholders.update(player, false) || animated || conditionsDue) refreshBoard(player, current)
            }
        }).repeat(Duration.ofMillis(50)).schedule()
        api = publicApi
        logger.info("VelociBoard started")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        stopping = true
        api = null
        refreshTask?.cancel()
        bridge.stop(this)
        preferences.close()
    }

    @Subscribe
    fun onPostLogin(event: PostLoginEvent) {
        val player = event.player
        val id = player.uniqueId
        preferenceStates[id] = Loading(player, 0)
        preferences.load(id).thenAccept { saved ->
            if (!stopping) proxy.scheduler.buildTask(this, Runnable { applyLoadedPreference(player, saved) }).schedule()
        }
    }

    @Subscribe
    fun onServerConnect(event: ServerPostConnectEvent) {
        val player = event.player
        previews.remove(player.uniqueId)
        bridge.clearOnSwitch(player)
        placeholders.update(player, true)
        refreshBoard(player, config)
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val player = event.player
        renderer.forget(player)
        placeholders.forget(player.uniqueId)
        bridge.forget(player)
        previews.remove(player.uniqueId)
        preferenceStates.computeIfPresent(player.uniqueId) { _, state -> if (state.player === player) null else state }
    }

    @Subscribe
    fun onKickedFromServer(event: KickedFromServerEvent) {
        bridge.forget(event.player)
        refreshBoard(event.player, config)
    }

    private fun refreshPlayer(playerId: UUID) {
        if (stopping) return
        proxy.scheduler.buildTask(this, Runnable {
            proxy.getPlayer(playerId).ifPresent { refreshBoard(it, config) }
        }).schedule()
    }

    private fun refreshBoard(player: Player, current: BoardConfig?) {
        val previewId = previews[player.uniqueId]
        if (previewId == HIDDEN_OVERRIDE) {
            renderer.remove(player)
            return
        }
        val preview = current?.boards?.firstOrNull { it.id == previewId }
        renderer.refresh(player, current, preview)
    }

    private fun wantsBoard(player: Player): Boolean {
        val state = preferenceStates[player.uniqueId]
        return state is Ready && state.player === player && !state.hidden
    }

    private fun applyLoadedPreference(player: Player, savedHidden: Boolean) {
        val id = player.uniqueId
        val loaded = AtomicReference<Loading>()
        preferenceStates.computeIfPresent(id) { _, state ->
            if (state is Loading && state.player === player) {
                loaded.set(state)
                val hidden = savedHidden xor (state.toggles % 2 != 0)
                if (state.toggles != 0) preferences.save(id, hidden)
                Ready(player, hidden)
            } else state
        }
        val pending = loaded.get() ?: return
        if (player.currentServer.isPresent) refreshBoard(player, config)
        if (pending.toggles != 0) sendToggleResult(player, !wantsBoard(player))
    }

    private fun toggle(source: CommandSource) {
        val player = source as? Player
        if (player == null) {
            source.sendMessage(Component.text("Only players can toggle their scoreboard.", NamedTextColor.RED))
            return
        }
        if (!player.hasPermission("velociboard.toggle")) {
            player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED))
            return
        }
        val id = player.uniqueId
        val changed = AtomicReference<Boolean>()
        preferenceStates.compute(id) { _, state ->
            if (state is Loading && state.player === player) {
                Loading(player, state.toggles + 1)
            } else {
                val hidden = !(state is Ready && state.player === player && state.hidden)
                preferences.save(id, hidden)
                changed.set(hidden)
                Ready(player, hidden)
            }
        }
        changed.get()?.let { hidden ->
            previews.remove(id)
            refreshBoard(player, config)
            sendToggleResult(player, hidden)
        }
    }

    private fun sendToggleResult(player: Player, hidden: Boolean) {
        player.sendMessage(Component.text(if (hidden) "Scoreboard hidden." else "Scoreboard shown.", NamedTextColor.GREEN))
    }

    private fun registerEmptyLuckPermsPlaceholders() {
        placeholders.register("luckperms_prefix") { "" }
        placeholders.register("luckperms_suffix") { "" }
        placeholders.register("luckperms_primary_group") { "" }
    }

    private fun reload(): Boolean {
        try {
            val loaded = BoardConfig.load(dataDirectory)
            placeholders.configureRefresh(loaded.placeholderRefresh)
            config = loaded
            for (player in proxy.allPlayers) {
                placeholders.update(player, true)
                refreshBoard(player, loaded)
            }
            return true
        } catch (error: IOException) {
            logger.error("Failed to load VelociBoard configuration: {}", error.message)
        } catch (error: IllegalArgumentException) {
            logger.error("Failed to load VelociBoard configuration: {}", error.message)
        }
        return false
    }

    private inner class BoardCommand : SimpleCommand {
        override fun execute(invocation: SimpleCommand.Invocation) {
            val args = invocation.arguments()
            val source = invocation.source()
            if (args.size == 1 && args[0].equals("reload", true)) {
                if (!permitted(source, "velociboard.reload")) return
                val loaded = reload()
                source.sendMessage(Component.text(
                    if (loaded) "VelociBoard configuration reloaded." else "VelociBoard reload failed. Check the proxy log.",
                    if (loaded) NamedTextColor.GREEN else NamedTextColor.RED))
                return
            }
            if (args.size == 1 && args[0].equals("toggle", true)) {
                toggle(source)
                return
            }
            if (args.size == 1 && args[0].equals("list", true)) {
                if (!permitted(source, "velociboard.admin")) return
                val boards = config?.boards?.joinToString { "${it.id} (priority ${it.priority})" }?.ifEmpty { "none" } ?: "none"
                source.sendMessage(Component.text("Boards: $boards", NamedTextColor.GRAY))
                return
            }
            if (args.size == 2 && args[0].equals("preview", true)) {
                if (!permitted(source, "velociboard.preview")) return
                val player = source as? Player
                if (player == null) {
                    source.sendMessage(Component.text("Only players can preview a board.", NamedTextColor.RED))
                    return
                }
                val current = config
                val board = current?.boards?.firstOrNull { it.id.equals(args[1], true) }
                if (board == null) {
                    source.sendMessage(Component.text("Unknown board: ${args[1]}", NamedTextColor.RED))
                    return
                }
                previews[player.uniqueId] = board.id
                refreshBoard(player, current)
                source.sendMessage(Component.text("Previewing ${board.id} until you switch servers or toggle.", NamedTextColor.GREEN))
                return
            }
            if (args.size == 1 && args[0].equals("placeholders", true)) {
                if (!permitted(source, "velociboard.admin")) return
                val names = placeholders.names().joinToString { "%$it%" }.ifEmpty { "none" }
                source.sendMessage(Component.text("Placeholders: $names", NamedTextColor.GRAY))
                source.sendMessage(Component.text("Paper bridge values use %backend_<name>%.", NamedTextColor.GRAY))
                return
            }
            if (args.size == 1 && args[0].equals("debug", true)) {
                if (!permitted(source, "velociboard.debug")) return
                val player = source as? Player
                if (player == null) {
                    source.sendMessage(Component.text("Only players can inspect their board.", NamedTextColor.RED))
                    return
                }
                debug(player)
                return
            }
            source.sendMessage(Component.text("/velociboard <reload|toggle|list|preview|placeholders|debug>", NamedTextColor.GRAY))
        }

        override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
            val args = invocation.arguments()
            val source = invocation.source()
            if (args.size <= 1) {
                val prefix = args.firstOrNull()?.lowercase() ?: ""
                val options = mutableListOf<String>()
                if (source.hasPermission("velociboard.reload") && "reload".startsWith(prefix)) options.add("reload")
                if (source.hasPermission("velociboard.toggle") && "toggle".startsWith(prefix)) options.add("toggle")
                for (command in listOf("list", "placeholders")) {
                    if (source.hasPermission("velociboard.admin") && command.startsWith(prefix)) options.add(command)
                }
                if (source.hasPermission("velociboard.preview") && "preview".startsWith(prefix)) options.add("preview")
                if (source.hasPermission("velociboard.debug") && "debug".startsWith(prefix)) options.add("debug")
                return options
            }
            if (args.size == 2 && args[0].equals("preview", true) && source.hasPermission("velociboard.preview")) {
                return config?.boards?.map { it.id }?.filter { it.startsWith(args[1].lowercase()) } ?: emptyList()
            }
            return emptyList()
        }
    }

    private fun permitted(source: CommandSource, permission: String): Boolean {
        if (source.hasPermission(permission)) return true
        source.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED))
        return false
    }

    private fun debug(player: Player) {
        val current = config
        if (current == null) {
            player.sendMessage(Component.text("No configuration loaded.", NamedTextColor.RED))
            return
        }
        val server = player.currentServer.map { it.server.serverInfo.name }.orElse("unknown")
        val resolved = HashMap<String, Component>()
        val active = current.select(server) { it.allowed(player, placeholders, resolved) }
        player.sendMessage(Component.text("Board: ${active?.id ?: "none"}" +
            if (wantsBoard(player)) "" else " (hidden)", NamedTextColor.GRAY))
        for (board in current.boards) {
            val reason = when {
                !board.matches(server) -> "server/disabled"
                !board.allowed(player, placeholders, resolved) -> "condition/permission"
                else -> "matched"
            }
            player.sendMessage(Component.text("${board.id}: $reason (priority ${board.priority})", NamedTextColor.GRAY))
        }
        if (active != null) {
            active.lines.forEachIndexed { index, line ->
                if (line.condition != null || line.permission != null) {
                    player.sendMessage(Component.text("line ${index + 1}: " +
                        if (line.visible(player, placeholders, resolved)) "shown" else "hidden", NamedTextColor.GRAY))
                }
            }
            val templates = active.title + " " + active.lines.joinToString(" ") { it.text }
            val names = PLACEHOLDER.findAll(templates).map { it.groupValues[1] }.toSortedSet()
            for (name in names) {
                player.sendMessage(Component.text("%$name% = ${placeholders.debugValue(player, name)}", NamedTextColor.GRAY))
            }
        }
        player.sendMessage(Component.text("Bridge: ${bridge.describe(player)}", NamedTextColor.GRAY))
    }

    private inner class PluginApi : VelociBoardAPI {
        private val placeholdersApi: VelociBoardAPI.Placeholders = PlaceholderAccess()

        override fun placeholders() = placeholdersApi

        override fun showBoard(player: Player, boardId: String): Boolean {
            val current = config
            if (stopping || current == null || proxy.getPlayer(player.uniqueId).orElse(null) !== player) return false
            val board = current.boards.firstOrNull { it.id == boardId } ?: return false
            previews[player.uniqueId] = board.id
            refreshPlayer(player.uniqueId)
            return true
        }

        override fun hideBoard(player: Player) {
            if (!stopping && proxy.getPlayer(player.uniqueId).orElse(null) === player) {
                previews[player.uniqueId] = HIDDEN_OVERRIDE
                refreshPlayer(player.uniqueId)
            }
        }

        override fun refresh(player: Player) {
            if (proxy.getPlayer(player.uniqueId).orElse(null) === player) refreshPlayer(player.uniqueId)
        }

        private inner class PlaceholderAccess : VelociBoardAPI.Placeholders {
            private val owned = HashSet<String>()

            @Synchronized
            override fun register(name: String, resolver: Function<Player, String>) {
                placeholders.register(name, resolver)
                owned.add(name)
                refreshAll()
            }

            @Synchronized
            override fun registerCached(name: String, interval: Duration, resolver: Function<Player, CompletionStage<String>>) {
                placeholders.registerCached(name, interval, resolver)
                owned.add(name)
                refreshAll()
            }

            @Synchronized
            override fun unregister(name: String) {
                if (owned.remove(name)) {
                    placeholders.unregister(name)
                    refreshAll()
                }
            }

            private fun refreshAll() {
                for (player in proxy.allPlayers) refreshPlayer(player.uniqueId)
            }
        }
    }

    private sealed interface PreferenceState { val player: Player }
    private data class Loading(override val player: Player, val toggles: Int) : PreferenceState
    private data class Ready(override val player: Player, val hidden: Boolean) : PreferenceState

    companion object {
        private val PLACEHOLDER = Regex("%([a-z][a-z0-9_]*)%")
        private const val HIDDEN_OVERRIDE = "\u0000hidden"
        @Volatile private var api: VelociBoardAPI? = null

        /** Returns the API after Velocity has initialized the plugin. */
        @JvmStatic
        fun getApi(): VelociBoardAPI = api ?: error("VelociBoard is not running")
    }
}
