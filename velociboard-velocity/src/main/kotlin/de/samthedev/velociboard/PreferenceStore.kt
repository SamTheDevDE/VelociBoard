package de.samthedev.velociboard

import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class PreferenceStore(private val database: Path, private val logger: Logger) {
    private val worker = Executors.newSingleThreadExecutor {
        Thread.ofPlatform().name("velociboard-preferences").unstarted(it)
    }
    private val loggedError = AtomicBoolean()
    private var connection: Connection? = null

    fun start() {
        CompletableFuture.runAsync({
            try {
                Files.createDirectories(database.parent)
                Class.forName("org.sqlite.JDBC")
                connection = DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}")
                connection!!.createStatement().use {
                    it.executeUpdate("CREATE TABLE IF NOT EXISTS preferences (player_id TEXT PRIMARY KEY, hidden INTEGER NOT NULL)")
                }
            } catch (error: Exception) {
                logError(error)
            }
        }, worker)
    }

    fun load(playerId: UUID): CompletableFuture<Boolean> = CompletableFuture.supplyAsync({
        val db = connection ?: return@supplyAsync false
        try {
            db.prepareStatement("SELECT hidden FROM preferences WHERE player_id = ?").use { statement ->
                statement.setString(1, playerId.toString())
                statement.executeQuery().use { result -> result.next() && result.getInt(1) != 0 }
            }
        } catch (error: SQLException) {
            logError(error)
            false
        }
    }, worker)

    fun save(playerId: UUID, hidden: Boolean): CompletableFuture<Void> = CompletableFuture.runAsync({
        val db = connection ?: return@runAsync
        try {
            db.prepareStatement("INSERT INTO preferences(player_id, hidden) VALUES(?, ?) " +
                "ON CONFLICT(player_id) DO UPDATE SET hidden = excluded.hidden").use { statement ->
                statement.setString(1, playerId.toString())
                statement.setInt(2, if (hidden) 1 else 0)
                statement.executeUpdate()
            }
        } catch (error: SQLException) {
            logError(error)
        }
    }, worker)

    fun close(): CompletableFuture<Void> {
        val closed = CompletableFuture.runAsync({
            try {
                connection?.close()
            } catch (error: SQLException) {
                logError(error)
            }
        }, worker)
        worker.shutdown()
        return closed
    }

    private fun logError(error: Exception) {
        if (loggedError.compareAndSet(false, true)) {
            logger.error("Could not use player preferences at {}: {}", database, error.message)
        }
    }
}
