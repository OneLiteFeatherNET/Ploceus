package net.onelitefeather.ploceus.persistence;

import net.onelitefeather.ploceus.domain.AuditEntry;
import net.onelitefeather.ploceus.domain.PloceusKey;
import org.hibernate.SessionFactory;

import java.util.List;

public class AuditRepository {

    private final SessionFactory sessionFactory;

    public AuditRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /// Append-only: entries are never updated or deleted.
    public void append(AuditEntry entry) {
        sessionFactory.inTransaction(session -> session.persist(entry));
    }

    public List<AuditEntry> findByTarget(PloceusKey targetId, int limit, int offset) {
        return sessionFactory.fromSession(session -> session
                .createQuery("from AuditEntry where targetId = :target order by occurredAt desc", AuditEntry.class)
                .setParameter("target", targetId)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList());
    }
}
