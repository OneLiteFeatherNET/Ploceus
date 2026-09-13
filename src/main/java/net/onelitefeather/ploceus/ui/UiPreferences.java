package net.onelitefeather.ploceus.ui;

import java.util.Optional;
import java.util.UUID;

/// Thin boundary so {@link UiRouter} can read and store a player's channel preference
/// without the `ui` package reaching into `persistence` directly (forbidden by
/// `ArchitectureTest#uiMustNotReachPersistence`). The persistence-backed implementation,
/// `PlayerPreferenceRepository`, lives in `persistence` and is bound to this interface in
/// `PloceusModule`.
public interface UiPreferences {

    Optional<UiChannel> find(UUID playerUuid);

    void save(UUID playerUuid, UiChannel channel);
}
