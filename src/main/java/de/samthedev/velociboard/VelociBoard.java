package de.samthedev.velociboard;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

@Plugin(id = "velociboard", name = "VelociBoard", version = "0.1.0-SNAPSHOT",
        description = "Sidebar scoreboards for Velocity networks", authors = {"SamTheDevDE"})
public final class VelociBoard {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private BoardConfig config;

    @Inject
    public VelociBoard(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        reload();
        CommandMeta meta = proxy.getCommandManager().metaBuilder("velociboard")
                .aliases("vboard", "vb")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(meta, new BoardCommand());
        logger.info("VelociBoard started");
    }

    private boolean reload() {
        try {
            config = BoardConfig.load(dataDirectory);
            return true;
        } catch (IOException | IllegalArgumentException error) {
            logger.error("Failed to load config.yml: {}", error.getMessage());
            return false;
        }
    }

    private final class BoardCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!invocation.source().hasPermission("velociboard.reload")) {
                    invocation.source().sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
                    return;
                }
                boolean loaded = reload();
                invocation.source().sendMessage(Component.text(
                        loaded ? "VelociBoard configuration reloaded." : "VelociBoard reload failed. Check the proxy log.",
                        loaded ? NamedTextColor.GREEN : NamedTextColor.RED));
                return;
            }
            invocation.source().sendMessage(Component.text("VelociBoard is in early development.", NamedTextColor.GRAY));
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            if (invocation.source().hasPermission("velociboard.reload")
                    && invocation.arguments().length <= 1
                    && "reload".startsWith(invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase())) {
                return List.of("reload");
            }
            return List.of();
        }
    }
}
