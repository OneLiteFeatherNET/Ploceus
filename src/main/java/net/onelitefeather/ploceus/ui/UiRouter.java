package net.onelitefeather.ploceus.ui;

import jakarta.inject.Inject;
import net.onelitefeather.ploceus.PloceusConfig;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class UiRouter {

    private final Map<UiChannel, ProjectUi> channels;
    private final UiPreferences preferences;
    private final PloceusConfig config;
    private final Map<UUID, UiChannel> cache = new ConcurrentHashMap<>();

    @Inject
    public UiRouter(Map<UiChannel, ProjectUi> channels, UiPreferences preferences,
                    PloceusConfig config) {
        this.channels = channels;
        this.preferences = preferences;
        this.config = config;
    }

    /// Pure decision, extracted so it is testable without a server:
    /// an explicit preference always wins, otherwise the protocol version decides.
    public static UiChannel choose(int protocolVersion, Optional<UiChannel> preference,
                                   int minDialogProtocol) {
        return preference.orElseGet(() ->
                protocolVersion >= minDialogProtocol ? UiChannel.DIALOG : UiChannel.INVENTORY);
    }

    public ProjectUi forPlayer(Player player) {
        UiChannel channel = cache.computeIfAbsent(player.getUniqueId(), uuid ->
                choose(player.getProtocolVersion(), preferences.find(uuid), config.minDialogProtocol()));
        return channels.get(channel);
    }

    public void setPreference(UUID playerUuid, UiChannel channel) {
        preferences.save(playerUuid, channel);
        cache.put(playerUuid, channel);
    }

    public void forget(UUID playerUuid) {
        cache.remove(playerUuid);
    }
}
