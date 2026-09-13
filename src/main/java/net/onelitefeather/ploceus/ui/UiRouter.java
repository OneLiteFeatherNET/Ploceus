package net.onelitefeather.ploceus.ui;

import jakarta.inject.Inject;
import net.onelitefeather.ploceus.PloceusConfig;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class UiRouter {

    private final Map<UiChannel, ProjectUi> channels;
    private final UiPreferences preferences;
    private final PloceusConfig config;
    private final SchedulerAdapter scheduler;
    private final Map<UUID, UiChannel> cache = new ConcurrentHashMap<>();

    @Inject
    public UiRouter(Map<UiChannel, ProjectUi> channels, UiPreferences preferences,
                    PloceusConfig config, SchedulerAdapter scheduler) {
        this.channels = channels;
        this.preferences = preferences;
        this.config = config;
        this.scheduler = scheduler;
    }

    /// Pure decision, extracted so it is testable without a server:
    /// an explicit preference always wins, otherwise the protocol version decides.
    public static UiChannel choose(int protocolVersion, Optional<UiChannel> preference,
                                   int minDialogProtocol) {
        return preference.orElseGet(() ->
                protocolVersion >= minDialogProtocol ? UiChannel.DIALOG : UiChannel.INVENTORY);
    }

    /// `getUniqueId()` and `getProtocolVersion()` are cheap Bukkit calls, so this stays on
    /// whatever thread the caller is on (normally the main thread). The actual routing
    /// decision is delegated to {@link #resolve}, which never blocks on I/O.
    public ProjectUi forPlayer(Player player) {
        return resolve(player.getUniqueId(), player.getProtocolVersion());
    }

    /// Extracted from {@link #forPlayer} so the non-blocking cache behaviour is testable
    /// without a `Player`. A cache hit answers immediately. A cache miss answers right away
    /// too, with the protocol-derived default, and separately kicks off an async lookup of
    /// the stored preference to warm the cache — so the player's own choice, if any, takes
    /// effect from their next call onward instead of blocking this one on a JDBC read.
    ProjectUi resolve(UUID uuid, int protocolVersion) {
        UiChannel cached = cache.get(uuid);
        if (cached != null) {
            return channels.get(cached);
        }
        int minDialogProtocol = config.minDialogProtocol();
        scheduler.async(() -> cache.computeIfAbsent(uuid,
                id -> choose(protocolVersion, preferences.find(id), minDialogProtocol)));
        return channels.get(choose(protocolVersion, Optional.empty(), minDialogProtocol));
    }

    /// Updates the cache immediately so the change is visible to the very next call on this
    /// thread, and writes it through to storage on the async executor so the caller (a
    /// command handler on the main thread) never blocks on the write.
    public void setPreference(UUID playerUuid, UiChannel channel) {
        cache.put(playerUuid, channel);
        scheduler.async(() -> preferences.save(playerUuid, channel));
    }

    public void forget(UUID playerUuid) {
        cache.remove(playerUuid);
    }
}
