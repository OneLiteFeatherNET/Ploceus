package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.persistence.ProjectRepository;
import net.onelitefeather.ploceus.persistence.TagRepository;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static net.onelitefeather.ploceus.domain.AuditEntry.TargetType.WORLD;

public class WorldService {

    private final WorldRepository worlds;
    private final ProjectRepository projects;
    private final TagRepository tags;
    private final AuditService audit;
    private final MultiverseAdapter multiverse;

    public WorldService(WorldRepository worlds, ProjectRepository projects, TagRepository tags,
                        AuditService audit, MultiverseAdapter multiverse) {
        this.worlds = worlds;
        this.projects = projects;
        this.tags = tags;
        this.audit = audit;
        this.multiverse = multiverse;
    }

    public Optional<ProjectWorld> find(PloceusKey key) { return worlds.findByKey(key); }
    public Optional<ProjectWorld> findByWorldName(String name) { return worlds.findByWorldName(name); }
    public List<ProjectWorld> all() { return worlds.findAll(); }
    public List<ProjectWorld> unassigned() { return worlds.findUnassigned(); }
    public List<ProjectWorld> byProject(Project project) { return worlds.findByProject(project); }
    public List<ProjectWorld> search(String term) { return worlds.search(term); }

    public OperationResult<ProjectWorld> assign(PloceusKey worldKey, PloceusKey projectKey, Actor actor) {
        Optional<Project> project = projects.findByKey(projectKey);
        if (project.isEmpty()) {
            return OperationResult.failure("project.unknown", projectKey.asString());
        }
        return mutate(worldKey, actor, world -> {
            String old = world.isUnassigned() ? "" : world.getProject().getKey().asString();
            world.setProject(project.get());
            audit.recordChange(WORLD, worldKey, actor, AuditAction.WORLD_ASSIGNED,
                    "project", old, projectKey.asString());
        });
    }

    public OperationResult<ProjectWorld> unassign(PloceusKey worldKey, Actor actor) {
        return mutate(worldKey, actor, world -> {
            String old = world.isUnassigned() ? "" : world.getProject().getKey().asString();
            world.setProject(null);
            audit.recordChange(WORLD, worldKey, actor, AuditAction.WORLD_UNASSIGNED,
                    "project", old, "");
        });
    }

    /// Changes the display name only. The world name and its folder stay fixed —
    /// Multiverse 5.8.1 cannot rename a world, and the folder anchors WorldGuard, Axiom
    /// and BlueMap. The new name is mirrored onto the Multiverse alias.
    ///
    /// The alias is mirrored FIRST, before anything is persisted or audited: this is the
    /// only ordering that keeps the operation all-or-nothing. Persisting first and mirroring
    /// second would report failure to the caller after the record was already renamed and
    /// audited, leaving the database and Multiverse disagreeing about the world's name.
    public OperationResult<ProjectWorld> rename(PloceusKey key, String displayName, Actor actor) {
        Optional<ProjectWorld> found = worlds.findByKey(key);
        if (found.isEmpty()) {
            return OperationResult.failure("world.unknown", key.asString());
        }
        ProjectWorld world = found.get();

        OperationResult<Void> alias = multiverse.setAlias(world.getWorldName(), displayName);
        if (!alias.isSuccess()) {
            return OperationResult.failure("world.alias.failed", world.getWorldName());
        }

        return mutate(key, actor, w -> {
            String old = w.getDisplayName();
            w.setDisplayName(displayName);
            audit.recordChange(WORLD, key, actor, AuditAction.WORLD_UPDATED,
                    "displayName", old, displayName);
        });
    }

    public OperationResult<ProjectWorld> describe(PloceusKey key, String description, Actor actor) {
        return mutate(key, actor, world -> {
            String old = world.getDescription();
            world.setDescription(description);
            audit.recordChange(WORLD, key, actor, AuditAction.WORLD_UPDATED,
                    "description", old, description);
        });
    }

    public OperationResult<ProjectWorld> addTag(PloceusKey worldKey, PloceusKey tagKey, Actor actor) {
        return mutate(worldKey, actor, world -> {
            world.addTag(tags.findOrCreate(tagKey));
            audit.recordEvent(WORLD, worldKey, actor, AuditAction.TAG_ADDED, tagKey.asString());
        });
    }

    public OperationResult<ProjectWorld> removeTag(PloceusKey worldKey, PloceusKey tagKey, Actor actor) {
        return mutate(worldKey, actor, world -> {
            world.removeTag(tags.findOrCreate(tagKey));
            audit.recordEvent(WORLD, worldKey, actor, AuditAction.TAG_REMOVED, tagKey.asString());
        });
    }

    public OperationResult<ProjectWorld> addAuthor(PloceusKey key, Author author, Actor actor) {
        return mutate(key, actor, world -> {
            world.addAuthor(author);
            audit.recordEvent(WORLD, key, actor, AuditAction.AUTHOR_ADDED, author.getLastKnownName());
        });
    }

    public OperationResult<ProjectWorld> removeAuthor(PloceusKey key, UUID playerUuid, Actor actor) {
        return mutate(key, actor, world -> {
            world.removeAuthor(playerUuid);
            audit.recordEvent(WORLD, key, actor, AuditAction.AUTHOR_REMOVED, playerUuid.toString());
        });
    }

    public OperationResult<ProjectWorld> setPinned(PloceusKey key, boolean pinned, Actor actor) {
        return mutate(key, actor, world -> {
            String old = String.valueOf(world.isPinned());
            world.setPinned(pinned);
            audit.recordChange(WORLD, key, actor, AuditAction.WORLD_UPDATED,
                    "pinned", old, String.valueOf(pinned));
        });
    }

    public OperationResult<ProjectWorld> setIdleMinutes(PloceusKey key, Integer minutes, Actor actor) {
        return mutate(key, actor, world -> {
            String old = String.valueOf(world.getIdleUnloadMinutes());
            world.setIdleUnloadMinutes(minutes);
            audit.recordChange(WORLD, key, actor, AuditAction.WORLD_UPDATED,
                    "idleUnloadMinutes", old, String.valueOf(minutes));
        });
    }

    private OperationResult<ProjectWorld> mutate(PloceusKey key, Actor actor, Consumer<ProjectWorld> change) {
        Optional<ProjectWorld> found = worlds.findByKey(key);
        if (found.isEmpty()) {
            return OperationResult.failure("world.unknown", key.asString());
        }
        ProjectWorld world = found.get();
        change.accept(world);
        world.setUpdatedBy(actor.uuid());
        worlds.save(world);
        return OperationResult.success(world);
    }
}
