package de.samthedev.velociboard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

final class PreferenceStore {
    private final Path database;
    private final Logger logger;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform().name("velociboard-preferences").unstarted(runnable));
    private final AtomicBoolean loggedError = new AtomicBoolean();
    private Connection connection;

    PreferenceStore(Path database, Logger logger) {
        this.database = database;
        this.logger = logger;
    }

    void start() {
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(database.getParent());
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS preferences (player_id TEXT PRIMARY KEY, hidden INTEGER NOT NULL)");
                }
            } catch (Exception error) {
                logError(error);
            }
        }, worker);
    }

    CompletableFuture<Boolean> load(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            if (connection == null) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT hidden FROM preferences WHERE player_id = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() && result.getInt(1) != 0;
                }
            } catch (SQLException error) {
                logError(error);
                return false;
            }
        }, worker);
    }

    CompletableFuture<Void> save(UUID playerId, boolean hidden) {
        return CompletableFuture.runAsync(() -> {
            if (connection == null) {
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO preferences(player_id, hidden) VALUES(?, ?) "
                            + "ON CONFLICT(player_id) DO UPDATE SET hidden = excluded.hidden")) {
                statement.setString(1, playerId.toString());
                statement.setInt(2, hidden ? 1 : 0);
                statement.executeUpdate();
            } catch (SQLException error) {
                logError(error);
            }
        }, worker);
    }

    CompletableFuture<Void> close() {
        CompletableFuture<Void> closed = CompletableFuture.runAsync(() -> {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException error) {
                    logError(error);
                }
            }
        }, worker);
        worker.shutdown();
        return closed;
    }

    private void logError(Exception error) {
        if (loggedError.compareAndSet(false, true)) {
            logger.error("Could not use player preferences at {}: {}", database, error.getMessage());
        }
    }
}
