package net.onelitefeather.ploceus.persistence;

import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.Project;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Every read here returns a **detached** entity: `fromSession` closes the session before
/// the result leaves the method. Anything the UI needs must therefore be loaded before that
/// happens — which is why `Project.worldCount` is filled in here rather than navigated
/// lazily by the caller.
public class ProjectRepository {

    private final SessionFactory sessionFactory;

    public ProjectRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public Optional<Project> findByKey(PloceusKey key) {
        return sessionFactory.fromSession(session ->
                Optional.ofNullable(session.find(Project.class, key))
                        .map(project -> withWorldCounts(session, List.of(project)).getFirst()));
    }

    public List<Project> findAll() {
        return sessionFactory.fromSession(session -> withWorldCounts(session, session
                .createQuery("from Project order by displayName", Project.class)
                .getResultList()));
    }

    /// Case-insensitive match against key, display name, description and tag key.
    public List<Project> search(String term) {
        String pattern = "%" + term.toLowerCase(java.util.Locale.ROOT) + "%";
        return sessionFactory.fromSession(session -> withWorldCounts(session, session.createQuery("""
                        select distinct p from Project p
                        left join p.tags t
                        where lower(p.displayName) like :pattern
                           or lower(p.description) like :pattern
                           or lower(str(p.key)) like :pattern
                           or lower(str(t.key)) like :pattern
                        order by p.displayName
                        """, Project.class)
                .setParameter("pattern", pattern)
                .getResultList()));
    }

    public void save(Project project) {
        sessionFactory.inTransaction(session -> session.merge(project));
    }

    public void delete(Project project) {
        sessionFactory.inTransaction(session -> session.remove(session.merge(project)));
    }

    /// One grouped count for the whole result, not one query per project and not a fetch of
    /// every world row. Runs while the session is still open, so the number survives
    /// detachment — the failure this replaced was `project.getWorlds().size()` on an entity
    /// whose session had already closed.
    private List<Project> withWorldCounts(Session session, List<Project> projects) {
        if (projects.isEmpty()) {
            return projects;
        }
        List<PloceusKey> keys = projects.stream().map(Project::getKey).toList();
        Map<PloceusKey, Integer> counts = new HashMap<>();
        session.createQuery("""
                        select w.project.key, count(w)
                        from ProjectWorld w
                        where w.project.key in :keys
                        group by w.project.key
                        """, Object[].class)
                .setParameter("keys", keys)
                .getResultList()
                .forEach(row -> counts.put((PloceusKey) row[0], ((Number) row[1]).intValue()));
        projects.forEach(project -> project.setWorldCount(counts.getOrDefault(project.getKey(), 0)));
        return projects;
    }
}
