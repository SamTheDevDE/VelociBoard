package de.samthedev.velociboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.Player;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import org.junit.jupiter.api.Test;

class LuckPermsPlaceholdersTest {
    @Test
    void readsLoadedUserWithoutLoadingFromStorage() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        LuckPerms luckPerms = mock(LuckPerms.class);
        UserManager users = mock(UserManager.class);
        User user = mock(User.class);
        CachedDataManager data = mock(CachedDataManager.class);
        CachedMetaData meta = mock(CachedMetaData.class);
        when(luckPerms.getUserManager()).thenReturn(users);
        when(users.getUser(id)).thenReturn(user);
        when(user.getCachedData()).thenReturn(data);
        when(data.getMetaData()).thenReturn(meta);
        when(meta.getPrefix()).thenReturn("§cAdmin");
        when(meta.getSuffix()).thenReturn("&7!");
        when(user.getPrimaryGroup()).thenReturn("staff");

        PlaceholderRegistry placeholders = new PlaceholderRegistry(ignored -> {});
        LuckPermsPlaceholders.install(placeholders, luckPerms);
        assertTrue(placeholders.update(player, true));
        assertEquals("Admin! staff", PlainTextComponentSerializer.plainText().serialize(
                placeholders.render(player, "%luckperms_prefix%%luckperms_suffix% %luckperms_primary_group%")));
        String formatted = MiniMessage.miniMessage().serialize(placeholders.render(player, "%luckperms_prefix%"));
        assertTrue(formatted.contains("red"));
    }
}
