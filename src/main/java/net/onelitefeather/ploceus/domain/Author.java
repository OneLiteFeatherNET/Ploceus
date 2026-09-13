package net.onelitefeather.ploceus.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;

@Embeddable
public class Author {

    @Column(name = "player_uuid", nullable = false)
    private UUID playerUuid;

    @Column(name = "last_known_name", nullable = false)
    private String lastKnownName;

    /// Free text such as "Terrain", "Redstone" or "Lead". Credits only — never a permission.
    @Column(name = "role")
    private String role;

    protected Author() { }

    public Author(UUID playerUuid, String lastKnownName, String role) {
        this.playerUuid = playerUuid;
        this.lastKnownName = lastKnownName;
        this.role = role;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public String getLastKnownName() { return lastKnownName; }
    public void setLastKnownName(String lastKnownName) { this.lastKnownName = lastKnownName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
