package net.onelitefeather.ploceus.persistence;

import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.Project;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import org.hibernate.SessionFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WorldRepository {

    private final SessionFactory sessionFactory;

    public WorldRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public Optional<ProjectWorld> findByKey(PloceusKey key) {
        return sessionFactory.fromSession(session ->
                Optional.ofNullable(session.find(ProjectWorld.class, key)));
    }

    public Optional<ProjectWorld> findByWorldName(String worldName) {
        return sessionFactory.fromSession(session -> session
                .createQuery("from ProjectWorld where worldName = :name", ProjectWorld.class)
                .setParameter("name", worldName)
                .uniqueResultOptional());
    }

    public List<ProjectWorld> findAll() {
        return sessionFactory.fromSession(session -> session
                .createQuery("from ProjectWorld order by displayName", ProjectWorld.class)
                .getResultList());
    }

    public List<ProjectWorld> findUnassigned() {
        return sessionFactory.fromSession(session -> session
                .createQuery("from ProjectWorld where project is null order by worldName", ProjectWorld.class)
                .getResultList());
    }

    public List<ProjectWorld> findByProject(Project project) {
        return sessionFactory.fromSession(session -> session
                .createQuery("from ProjectWorld where project = :project order by displayName", ProjectWorld.class)
                .setParameter("project", project)
                .getResultList());
    }

    public List<ProjectWorld> search(String term) {
        String pattern = "%" + term.toLowerCase(java.util.Locale.ROOT) + "%";
        return sessionFactory.fromSession(session -> session.createQuery("""
                        select distinct w from ProjectWorld w
                        left join w.tags t
                        where lower(w.displayName) like :pattern
                           or lower(w.worldName) like :pattern
                           or lower(w.description) like :pattern
                           or lower(str(t.key)) like :pattern
                        order by w.displayName
                        """, ProjectWorld.class)
                .setParameter("pattern", pattern)
                .getResultList());
    }

    public void save(ProjectWorld world) {
        sessionFactory.inTransaction(session -> session.merge(world));
    }

    public void delete(ProjectWorld world) {
        sessionFactory.inTransaction(session -> session.remove(session.merge(world)));
    }

    /// Bulk update for the batched visit tracker. One statement per entry, one transaction
    /// for the whole batch — never one transaction per player movement.
    public void updateLastVisited(Map<PloceusKey, Instant> visits) {
        if (visits.isEmpty()) {
            return;
        }
        sessionFactory.inTransaction(session -> visits.forEach((key, at) -> session
                .createMutationQuery("update ProjectWorld set lastVisitedAt = :at where key = :key")
                .setParameter("at", at)
                .setParameter("key", key)
                .executeUpdate()));
    }
}
