package net.onelitefeather.ploceus.ui.inventory;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Proves the inventory channel's execute-time permission check, mirroring
/// `DialogPermissionsTest`: an `InventoryClickEvent` is exactly as client-driven as a
/// dialog's custom-click callback (see `InventoryUi`'s class Javadoc), so `InventoryUi.item`
/// must not let a player without the required permission reach the action it guards.
///
/// This project has no MockBukkit-style harness (see `CommandSyntaxTest`'s note on the same
/// gap), so building a real `GuiItem`/`ItemStack` is not attempted here — it doesn't need to
/// be, because `item`'s denied-permission branch returns `null` before constructing either
/// one. That early return is exactly what is being tested.
class InventoryPermissionsTest {

    /// `InventoryUi`'s other constructor arguments are never touched on the denied path
    /// tested here (no service call, no `Plugin` use), so they are safely left `null`.
    private final InventoryUi inventoryUi =
            new InventoryUi(null, null, null, null, null, null, null, null, null);

    /// A `Player` test double whose `hasPermission` always answers `false`, in the same
    /// style `DialogPermissionsTest.playerDeniedEverything` uses for the dialog channel.
    private static Player playerDeniedEverything() {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("hasPermission")) {
                return false;
            }
            return switch (method.getReturnType().getName()) {
                case "boolean" -> false;
                case "int", "short", "byte" -> 0;
                case "long" -> 0L;
                case "float" -> 0f;
                case "double" -> 0d;
                case "void" -> null;
                default -> null;
            };
        };
        return (Player) Proxy.newProxyInstance(
                InventoryPermissionsTest.class.getClassLoader(), new Class<?>[] {Player.class}, handler);
    }

    @Test
    void itemDoesNotExistAndTheServiceActionNeverRunsWhenThePlayerLacksThePermission() {
        AtomicBoolean serviceReached = new AtomicBoolean(false);
        Player player = playerDeniedEverything();

        GuiItem item = inventoryUi.item(player, "ploceus.world.teleport", Material.GRASS_BLOCK,
                Component.text("some-world"), () -> serviceReached.set(true));

        assertThat(item).isNull();
        assertThat(serviceReached).isFalse();
    }
}
