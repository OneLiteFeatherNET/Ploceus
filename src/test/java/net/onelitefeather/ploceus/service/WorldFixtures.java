package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.persistence.*;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;

import java.time.Instant;
import java.util.*;

/// In-memory double for `WorldRepository`, mirroring `InMemoryFixtures`' approach for
/// `ProjectRepository` so world-related services can be tested without a database or a
/// server. Each instance owns its own storage and audit trail.
final class WorldFixtures {

    private final List<AuditEntry> audit = new ArrayList<>();
    private final Map<PloceusKey, ProjectWorld> storage = new LinkedHashMap<>();

    private final WorldRepository worlds = new WorldRepository(null) {
        @Override public Optional<ProjectWorld> findByKey(PloceusKey key) {
            return Optional.ofNullable(storage.get(key));
        }
        @Override public Optional<ProjectWorld> findByWorldName(String worldName) {
            return storage.values().stream()
                    .filter(world -> world.getWorldName().equals(worldName))
                    .findFirst();
        }
        @Override public List<ProjectWorld> findAll() { return List.copyOf(storage.values()); }
        @Override public List<ProjectWorld> findUnassigned() {
            return storage.values().stream().filter(ProjectWorld::isUnassigned).toList();
        }
        @Override public List<ProjectWorld> findByProject(Project project) {
            return storage.values().stream()
                    .filter(world -> !world.isUnassigned() && world.getProject().getKey().equals(project.getKey()))
                    .toList();
        }
        @Override public List<ProjectWorld> search(String term) { return findAll(); }
        @Override public void save(ProjectWorld world) { storage.put(world.getKey(), world); }
        @Override public void delete(ProjectWorld world) { storage.remove(world.getKey()); }
        @Override public void updateLastVisited(Map<PloceusKey, Instant> visits) {
            visits.forEach((key, at) -> {
                ProjectWorld world = storage.get(key);
                if (world != null) {
                    world.setLastVisitedAt(at);
                }
            });
        }
    };

    private final ProjectRepository projects = new ProjectRepository(null) {
        @Override public Optional<Project> findByKey(PloceusKey key) { return Optional.empty(); }
        @Override public List<Project> findAll() { return List.of(); }
        @Override public List<Project> search(String term) { return List.of(); }
        @Override public void save(Project project) { }
        @Override public void delete(Project project) { }
    };

    private final TagRepository tags = new TagRepository(null) {
        private final Map<PloceusKey, Tag> known = new LinkedHashMap<>();
        @Override public Tag findOrCreate(PloceusKey key) {
            return known.computeIfAbsent(key, Tag::new);
        }
        @Override public List<Tag> findAll() { return List.copyOf(known.values()); }
    };

    private final AuditRepository auditRepository = new AuditRepository(null) {
        @Override public void append(AuditEntry entry) { audit.add(entry); }
        @Override public List<AuditEntry> findByTarget(PloceusKey t, int limit, int offset) {
            return audit.reversed().stream()
                    .filter(e -> e.getTargetId().equals(t))
                    .skip(offset).limit(limit).toList();
        }
    };

    private final AuditService auditService = new AuditService(auditRepository);

    private final MultiverseAdapter multiverse;

    /// Runs every scheduler hop in-line, the same double `WorldCreationServiceTest.Fixture`
    /// and `DialogPermissionsTest` use, so `importService().importOne(...).join()` returns
    /// immediately without a real thread hop.
    private final SchedulerAdapter scheduler = new SchedulerAdapter(null) {
        @Override public void async(Runnable work) { work.run(); }
        @Override public void sync(Runnable work) { work.run(); }
    };

    /// Default double: an always-succeeding alias mirror, import and auto-load, since the
    /// real `MultiverseAdapter` would call into a `null` `MultiverseCoreApi` and throw as
    /// soon as any of them is invoked. Tests that need to see a failure from one of these
    /// pass their own `MultiverseAdapter` override instead.
    WorldFixtures() {
        this(new MultiverseAdapter(null, null) {
            @Override
            public OperationResult<Void> setAlias(String worldName, String alias) {
                return OperationResult.success(null);
            }
            @Override
            public OperationResult<Void> setAutoLoad(String worldName, boolean autoLoad) {
                return OperationResult.success(null);
            }
            @Override
            public OperationResult<String> importWorld(String worldName) {
                return OperationResult.success(worldName);
            }
        });
    }

    /// Lets a test supply a `MultiverseAdapter` subclass whose methods are overridden to
    /// return a canned `OperationResult`, e.g. to exercise `rename`'s alias-failure path or
    /// `importOne`'s Multiverse-refusal path.
    WorldFixtures(MultiverseAdapter multiverse) {
        this.multiverse = multiverse;
    }

    WorldService worldService() {
        return new WorldService(worlds, projects, tags, auditService, multiverse);
    }

    WorldImportService importService() {
        return new WorldImportService(worlds, auditService, multiverse, scheduler);
    }

    AuditEntry lastAudit() { return audit.getLast(); }

    int auditCount() { return audit.size(); }
}
