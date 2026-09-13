package net.onelitefeather.ploceus.listener;

import jakarta.inject.Inject;
import net.onelitefeather.ploceus.service.VisitTracker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Instant;

public class WorldVisitListener implements Listener {

    private final VisitTracker tracker;

    @Inject
    public WorldVisitListener(VisitTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        tracker.recordVisit(event.getPlayer().getWorld().getName(), Instant.now());
    }

    /// Records both sides: the world being left gets a fresh timestamp so its idle timer
    /// starts now, and the world being entered is marked as visited.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Instant now = Instant.now();
        tracker.recordVisit(event.getFrom().getName(), now);
        tracker.recordVisit(event.getPlayer().getWorld().getName(), now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracker.recordVisit(event.getPlayer().getWorld().getName(), Instant.now());
    }
}
