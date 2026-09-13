package net.onelitefeather.ploceus.persistence;

import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.Project;
import org.hibernate.SessionFactory;

import java.util.List;
import java.util.Optional;

public class ProjectRepository {

    private final SessionFactory sessionFactory;

    public ProjectRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public Optional<Project> findByKey(PloceusKey key) {
        return sessionFactory.fromSession(session ->
                Optional.ofNullable(session.find(Project.class, key)));
    }

    public List<Project> findAll() {
        return sessionFactory.fromSession(session -> session
                .createQuery("from Project order by displayName", Project.class)
                .getResultList());
    }

    /// Case-insensitive match against key, display name, description and tag key.
    public List<Project> search(String term) {
        String pattern = "%" + term.toLowerCase(java.util.Locale.ROOT) + "%";
        return sessionFactory.fromSession(session -> session.createQuery("""
                        select distinct p from Project p
                        left join p.tags t
                        where lower(p.displayName) like :pattern
                           or lower(p.description) like :pattern
                           or lower(str(p.key)) like :pattern
                           or lower(str(t.key)) like :pattern
                        order by p.displayName
                        """, Project.class)
                .setParameter("pattern", pattern)
                .getResultList());
    }

    public void save(Project project) {
        sessionFactory.inTransaction(session -> session.merge(project));
    }

    public void delete(Project project) {
        sessionFactory.inTransaction(session -> session.remove(session.merge(project)));
    }
}
