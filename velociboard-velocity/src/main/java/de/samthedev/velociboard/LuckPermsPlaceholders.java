package de.samthedev.velociboard;

import com.velocitypowered.api.proxy.Player;
import java.time.Duration;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

final class LuckPermsPlaceholders {
    static void install(PlaceholderRegistry placeholders) {
        install(placeholders, LuckPermsProvider.get());
    }

    static void install(PlaceholderRegistry placeholders, LuckPerms luckPerms) {
        placeholders.registerPolledComponent("luckperms_prefix", Duration.ofSeconds(1),
                player -> format(read(luckPerms, player, user -> user.getCachedData().getMetaData().getPrefix())));
        placeholders.registerPolledComponent("luckperms_suffix", Duration.ofSeconds(1),
                player -> format(read(luckPerms, player, user -> user.getCachedData().getMetaData().getSuffix())));
        placeholders.registerPolled("luckperms_primary_group", Duration.ofSeconds(1),
                player -> read(luckPerms, player, User::getPrimaryGroup));
    }

    private static String read(LuckPerms luckPerms, Player player, Function<User, String> value) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return "";
        }
        String result = value.apply(user);
        return result == null ? "" : result;
    }

    private static Component format(String value) {
        if (value.indexOf('§') >= 0) {
            return LegacyComponentSerializer.legacySection().deserialize(value);
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value);
    }

    private LuckPermsPlaceholders() {
    }
}
