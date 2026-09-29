package de.samthedev.velociboard

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import java.time.Duration

internal object LuckPermsPlaceholders {
    fun install(placeholders: PlaceholderRegistry) = install(placeholders, LuckPermsProvider.get())

    fun install(placeholders: PlaceholderRegistry, luckPerms: LuckPerms) {
        placeholders.registerPolledComponent("luckperms_prefix", Duration.ofSeconds(1)) { player ->
            format(read(luckPerms, player) { it.cachedData.metaData.prefix })
        }
        placeholders.registerPolledComponent("luckperms_suffix", Duration.ofSeconds(1)) { player ->
            format(read(luckPerms, player) { it.cachedData.metaData.suffix })
        }
        placeholders.registerPolled("luckperms_primary_group", Duration.ofSeconds(1)) { player ->
            read(luckPerms, player) { it.primaryGroup }
        }
    }

    private fun read(luckPerms: LuckPerms, player: Player, value: (User) -> String?): String {
        val user = luckPerms.userManager.getUser(player.uniqueId) ?: return ""
        return value(user).orEmpty()
    }

    private fun format(value: String): Component = if ('§' in value)
        LegacyComponentSerializer.legacySection().deserialize(value)
    else LegacyComponentSerializer.legacyAmpersand().deserialize(value)
}
