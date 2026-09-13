package net.onelitefeather.ploceus.persistence;

import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.Tag;
import org.hibernate.SessionFactory;

import java.util.List;

public class TagRepository {

    private final SessionFactory sessionFactory;

    public TagRepository(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public Tag findOrCreate(PloceusKey key) {
        return sessionFactory.fromTransaction(session -> {
            Tag existing = session.find(Tag.class, key);
            if (existing != null) {
                return existing;
            }
            Tag created = new Tag(key);
            session.persist(created);
            return created;
        });
    }

    public List<Tag> findAll() {
        return sessionFactory.fromSession(session ->
                session.createQuery("from Tag order by key", Tag.class).getResultList());
    }
}
