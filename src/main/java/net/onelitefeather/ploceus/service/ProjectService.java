package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import net.onelitefeather.ploceus.persistence.ProjectRepository;
import net.onelitefeather.ploceus.persistence.TagRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static net.onelitefeather.ploceus.domain.AuditEntry.TargetType.PROJECT;

public class ProjectService {

    private final ProjectRepository projects;
    private final TagRepository tags;
    private final AuditService audit;

    public ProjectService(ProjectRepository projects, TagRepository tags, AuditService audit) {
        this.projects = projects;
        this.tags = tags;
        this.audit = audit;
    }

    public Optional<Project> find(PloceusKey key) {
        return projects.findByKey(key);
    }

    public List<Project> all() {
        return projects.findAll();
    }

    public List<Project> search(String term) {
        return projects.search(term);
    }

    public OperationResult<Project> create(PloceusKey key, String displayName, Actor actor) {
        if (projects.findByKey(key).isPresent()) {
            return OperationResult.failure("project.exists", key.asString());
        }
        Project project = new Project(key, displayName);
        project.setCreatedBy(actor.uuid());
        project.setUpdatedBy(actor.uuid());
        projects.save(project);
        audit.recordEvent(PROJECT, key, actor, AuditAction.PROJECT_CREATED, displayName);
        return OperationResult.success(project);
    }

    public OperationResult<Project> rename(PloceusKey key, String displayName, Actor actor) {
        return mutate(key, actor, project -> {
            String old = project.getDisplayName();
            project.setDisplayName(displayName);
            audit.recordChange(PROJECT, key, actor, AuditAction.PROJECT_UPDATED,
                    "displayName", old, displayName);
        });
    }

    public OperationResult<Project> describe(PloceusKey key, String description, Actor actor) {
        return mutate(key, actor, project -> {
            String old = project.getDescription();
            project.setDescription(description);
            audit.recordChange(PROJECT, key, actor, AuditAction.PROJECT_UPDATED,
                    "description", old, description);
        });
    }

    public OperationResult<Project> setStatus(PloceusKey key, ProjectStatus status, Actor actor) {
        return mutate(key, actor, project -> {
            String old = project.getStatus().name();
            project.setStatus(status);
            audit.recordChange(PROJECT, key, actor, AuditAction.PROJECT_UPDATED,
                    "status", old, status.name());
        });
    }

    public OperationResult<Project> setWorldDefaults(PloceusKey key, WorldDefaults defaults, Actor actor) {
        return mutate(key, actor, project -> {
            project.setWorldDefaults(defaults);
            audit.recordEvent(PROJECT, key, actor, AuditAction.PROJECT_UPDATED,
                    "worldDefaults=" + defaults.getGenerator());
        });
    }

    public OperationResult<Project> addTag(PloceusKey projectKey, PloceusKey tagKey, Actor actor) {
        return mutate(projectKey, actor, project -> {
            project.addTag(tags.findOrCreate(tagKey));
            audit.recordEvent(PROJECT, projectKey, actor, AuditAction.TAG_ADDED, tagKey.asString());
        });
    }

    public OperationResult<Project> removeTag(PloceusKey projectKey, PloceusKey tagKey, Actor actor) {
        return mutate(projectKey, actor, project -> {
            project.removeTag(tags.findOrCreate(tagKey));
            audit.recordEvent(PROJECT, projectKey, actor, AuditAction.TAG_REMOVED, tagKey.asString());
        });
    }

    public OperationResult<Project> addAuthor(PloceusKey key, Author author, Actor actor) {
        return mutate(key, actor, project -> {
            project.addAuthor(author);
            audit.recordEvent(PROJECT, key, actor, AuditAction.AUTHOR_ADDED,
                    author.getLastKnownName() + " (" + author.getRole() + ")");
        });
    }

    public OperationResult<Project> removeAuthor(PloceusKey key, UUID playerUuid, Actor actor) {
        return mutate(key, actor, project -> {
            project.removeAuthor(playerUuid);
            audit.recordEvent(PROJECT, key, actor, AuditAction.AUTHOR_REMOVED, playerUuid.toString());
        });
    }

    /// Worlds are not deleted with the project; the foreign key is set to null, which
    /// returns them to the unassigned pool.
    public OperationResult<Project> delete(PloceusKey key, Actor actor) {
        Optional<Project> found = projects.findByKey(key);
        if (found.isEmpty()) {
            return OperationResult.failure("project.unknown", key.asString());
        }
        projects.delete(found.get());
        audit.recordEvent(PROJECT, key, actor, AuditAction.PROJECT_DELETED, key.asString());
        return OperationResult.success(found.get());
    }

    private OperationResult<Project> mutate(PloceusKey key, Actor actor,
                                            java.util.function.Consumer<Project> change) {
        Optional<Project> found = projects.findByKey(key);
        if (found.isEmpty()) {
            return OperationResult.failure("project.unknown", key.asString());
        }
        Project project = found.get();
        change.accept(project);
        project.setUpdatedBy(actor.uuid());
        projects.save(project);
        return OperationResult.success(project);
    }
}
