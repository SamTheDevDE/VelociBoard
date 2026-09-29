package de.samthedev.velociboard.api

import com.velocitypowered.api.proxy.Player
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.function.Function

interface VelociBoardAPI {
    fun placeholders(): Placeholders

    /** Shows a configured board until the player changes server or disconnects. */
    fun showBoard(player: Player, boardId: String): Boolean

    /** Hides the sidebar until the player changes server or disconnects. */
    fun hideBoard(player: Player)

    fun refresh(player: Player)

    interface Placeholders {
        /** The resolver may run during rendering, so read only fast local state. */
        fun register(name: String, resolver: Function<Player, String>)

        /** Holds the last value while an asynchronous refresh runs. */
        fun registerCached(name: String, interval: Duration, resolver: Function<Player, CompletionStage<String>>)

        fun unregister(name: String)
    }
}
