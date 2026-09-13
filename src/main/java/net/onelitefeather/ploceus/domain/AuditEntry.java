package net.onelitefeather.ploceus.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ploceus_audit", indexes = {
        @Index(name = "idx_audit_target", columnList = "target_id"),
        @Index(name = "idx_audit_at", columnList = "occurred_at")
})
public class AuditEntry {

    public enum TargetType { PROJECT, WORLD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false, length = 255)
    private PloceusKey targetId;

    @Column(name = "actor_uuid")
    private UUID actorUuid;

    @Column(name = "actor_name", nullable = false)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private AuditAction action;

    @Column(name = "field")
    private String field;

    @Column(name = "old_value", length = 2000)
    private String oldValue;

    @Column(name = "new_value", length = 2000)
    private String newValue;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEntry() { }

    public AuditEntry(TargetType targetType, PloceusKey targetId, UUID actorUuid, String actorName,
                      AuditAction action, String field, String oldValue, String newValue) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.actorUuid = actorUuid;
        this.actorName = actorName;
        this.action = action;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    public TargetType getTargetType() { return targetType; }
    public PloceusKey getTargetId() { return targetId; }
    public UUID getActorUuid() { return actorUuid; }
    public String getActorName() { return actorName; }
    public AuditAction getAction() { return action; }
    public String getField() { return field; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public Instant getOccurredAt() { return occurredAt; }
}
