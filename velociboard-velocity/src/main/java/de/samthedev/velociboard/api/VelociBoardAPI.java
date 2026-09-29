package de.samthedev.velociboard.api;

import com.velocitypowered.api.proxy.Player;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public interface VelociBoardAPI {
    Placeholders placeholders();

    /** Shows a configured board until the player changes server or disconnects. */
    boolean showBoard(Player player, String boardId);

    /** Hides the sidebar until the player changes server or disconnects. */
    void hideBoard(Player player);

    void refresh(Player player);

    interface Placeholders {
        /** Registers a fast, local value. The resolver may run during rendering. */
        void register(String name, Function<Player, String> resolver);

        /** Registers a value resolved off the render path and held for the given interval. */
        void registerCached(String name, Duration interval, Function<Player, CompletionStage<String>> resolver);

        void unregister(String name);
    }
}
