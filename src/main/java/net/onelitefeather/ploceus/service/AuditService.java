package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.persistence.AuditRepository;

import java.util.List;
import java.util.Objects;

public class AuditService {

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    /// No-op when nothing actually changed, so the trail stays meaningful.
    public void recordChange(AuditEntry.TargetType targetType, PloceusKey targetId, Actor actor,
                             AuditAction action, String field, String oldValue, String newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        repository.append(new AuditEntry(targetType, targetId, actor.uuid(), actor.name(),
                action, field, oldValue, newValue));
    }

    public void recordEvent(AuditEntry.TargetType targetType, PloceusKey targetId, Actor actor,
                            AuditAction action, String detail) {
        repository.append(new AuditEntry(targetType, targetId, actor.uuid(), actor.name(),
                action, null, null, detail));
    }

    public List<AuditEntry> history(PloceusKey targetId, int limit, int offset) {
        return repository.findByTarget(targetId, limit, offset);
    }
}
