package de.samthedev.velociboard

import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.luckperms.api.LuckPerms
import net.luckperms.api.cacheddata.CachedDataManager
import net.luckperms.api.cacheddata.CachedMetaData
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class LuckPermsPlaceholdersTest {
    @Test
    fun readsLoadedUserWithoutLoadingFromStorage() {
        val player = Mockito.mock(Player::class.java)
        val id = UUID.randomUUID()
        Mockito.`when`(player.uniqueId).thenReturn(id)
        val luckPerms = Mockito.mock(LuckPerms::class.java)
        val users = Mockito.mock(UserManager::class.java)
        val user = Mockito.mock(User::class.java)
        val data = Mockito.mock(CachedDataManager::class.java)
        val meta = Mockito.mock(CachedMetaData::class.java)
        Mockito.`when`(luckPerms.userManager).thenReturn(users)
        Mockito.`when`(users.getUser(id)).thenReturn(user)
        Mockito.`when`(user.cachedData).thenReturn(data)
        Mockito.`when`(data.metaData).thenReturn(meta)
        Mockito.`when`(meta.prefix).thenReturn("§cAdmin")
        Mockito.`when`(meta.suffix).thenReturn("&7!")
        Mockito.`when`(user.primaryGroup).thenReturn("staff")

        val placeholders = PlaceholderRegistry { }
        LuckPermsPlaceholders.install(placeholders, luckPerms)
        assertTrue(placeholders.update(player, true))
        assertEquals("Admin! staff", PlainTextComponentSerializer.plainText().serialize(
            placeholders.render(player, "%luckperms_prefix%%luckperms_suffix% %luckperms_primary_group%")))
        val formatted = MiniMessage.miniMessage().serialize(placeholders.render(player, "%luckperms_prefix%"))
        assertTrue(formatted.contains("red"))
    }
}
