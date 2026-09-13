package net.onelitefeather.ploceus.listener;

import jakarta.inject.Inject;
import net.onelitefeather.ploceus.ui.UiRouter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/// Kept separate from {@link WorldVisitListener}: that one tracks world visits for the
/// reaper, this one only bounds the size of {@link UiRouter}'s in-memory channel cache by
/// dropping an entry once its player disconnects.
public class UiRouterListener implements Listener {

    private final UiRouter router;

    @Inject
    public UiRouterListener(UiRouter router) {
        this.router = router;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        router.forget(event.getPlayer().getUniqueId());
    }
}
