package net.onelitefeather.ploceus.listener;

import jakarta.inject.Inject;
import net.onelitefeather.ploceus.ui.UiRouter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/// Kept separate from {@link WorldVisitListener}: that one tracks world visits for the
/// reaper, this one owns the lifetime of {@link UiRouter}'s in-memory channel cache — warmed
/// on join, dropped on quit.
public class UiRouterListener implements Listener {

    private final UiRouter router;

    @Inject
    public UiRouterListener(UiRouter router) {
        this.router = router;
    }

    /// Reads the player's stored channel preference into the cache before they can type
    /// anything. Without this, the first command after every login is answered from the
    /// protocol-derived default, because the cache was emptied on their last quit and
    /// `UiRouter.resolve` does not block on the lookup it starts.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        router.warm(event.getPlayer().getUniqueId(), event.getPlayer().getProtocolVersion());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        router.forget(event.getPlayer().getUniqueId());
    }
}
