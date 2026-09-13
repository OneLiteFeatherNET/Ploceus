package net.onelitefeather.ploceus.persistence;

import net.onelitefeather.ploceus.ui.UiChannel;
import net.onelitefeather.ploceus.ui.UiPreferences;
import org.hibernate.SessionFactory;

import java.util.Optional;
import java.util.UUID;

/// Backs {@link UiPreferences} for {@code UiRouter}. Uses a native query, because the
/// preference is stored in `ploceus_player_preference` (changeset `001-player-preference`)
/// without being mapped as a Hibernate entity.
public class PlayerPreferenceRepository implements UiPreferences {

    private final SessionFactory sessionFactory;

    public PlayerPreferenceRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Optional<UiChannel> find(UUID playerUuid) {
        return sessionFactory.fromSession(session -> session
                .createNativeQuery("select ui_channel from ploceus_player_preference where player_uuid = :id",
                        String.class)
                .setParameter("id", playerUuid)
                .uniqueResultOptional()
                .map(UiChannel::valueOf));
    }

    @Override
    public void save(UUID playerUuid, UiChannel channel) {
        sessionFactory.inTransaction(session -> {
            int updated = session.createNativeMutationQuery(
                            "update ploceus_player_preference set ui_channel = :channel where player_uuid = :id")
                    .setParameter("channel", channel.name())
                    .setParameter("id", playerUuid)
                    .executeUpdate();
            if (updated == 0) {
                session.createNativeMutationQuery(
                                "insert into ploceus_player_preference (player_uuid, ui_channel) values (:id, :channel)")
                        .setParameter("id", playerUuid)
                        .setParameter("channel", channel.name())
                        .executeUpdate();
            }
        });
    }
}
