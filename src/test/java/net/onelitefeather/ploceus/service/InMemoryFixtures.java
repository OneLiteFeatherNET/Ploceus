package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.persistence.*;

import java.util.*;

/// In-memory doubles. The repositories are the only seam the services depend on, so
/// subclassing them with map-backed storage is enough to test every service rule.
final class InMemoryFixtures {

    private static final List<AuditEntry> AUDIT = new ArrayList<>();

    static ProjectService projectService() {
        AUDIT.clear();
        Map<PloceusKey, Project> storage = new LinkedHashMap<>();

        ProjectRepository projects = new ProjectRepository(null) {
            @Override public Optional<Project> findByKey(PloceusKey key) {
                return Optional.ofNullable(storage.get(key));
            }
            @Override public List<Project> findAll() { return List.copyOf(storage.values()); }
            @Override public List<Project> search(String term) { return findAll(); }
            @Override public void save(Project project) { storage.put(project.getKey(), project); }
            @Override public void delete(Project project) { storage.remove(project.getKey()); }
        };

        TagRepository tags = new TagRepository(null) {
            private final Map<PloceusKey, Tag> known = new LinkedHashMap<>();
            @Override public Tag findOrCreate(PloceusKey key) {
                return known.computeIfAbsent(key, Tag::new);
            }
            @Override public List<Tag> findAll() { return List.copyOf(known.values()); }
        };

        AuditRepository audit = new AuditRepository(null) {
            @Override public void append(AuditEntry entry) { AUDIT.add(entry); }
            @Override public List<AuditEntry> findByTarget(PloceusKey t, int limit, int offset) {
                return AUDIT.reversed().stream()
                        .filter(e -> e.getTargetId().equals(t))
                        .skip(offset).limit(limit).toList();
            }
        };

        return new ProjectService(projects, tags, new AuditService(audit));
    }

    static AuditEntry lastAudit() { return AUDIT.getLast(); }

    static int auditCount() { return AUDIT.size(); }

    private InMemoryFixtures() { }
}
