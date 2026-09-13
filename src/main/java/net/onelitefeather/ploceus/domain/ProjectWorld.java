package net.onelitefeather.ploceus.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "ploceus_world")
public class ProjectWorld extends AuditableEntity implements Taggable, Authored, Describable {

    @Id
    @Column(name = "world_key", length = 255)
    private PloceusKey key;

    /// The Multiverse world name. Immutable: the folder anchors WorldGuard, Axiom and BlueMap.
    @Column(name = "world_name", nullable = false, unique = true, updatable = false)
    private String worldName;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "project_key")
    private Project project;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "description", length = 2000)
    private String description = "";

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "ploceus_world_tag",
            joinColumns = @JoinColumn(name = "world_key"),
            inverseJoinColumns = @JoinColumn(name = "tag_key"))
    private Set<Tag> tags = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ploceus_world_author", joinColumns = @JoinColumn(name = "world_key"))
    private List<Author> authors = new ArrayList<>();

    @Column(name = "last_visited_at")
    private Instant lastVisitedAt;

    /// Null means fall back to the global default from config.yml.
    @Column(name = "idle_unload_minutes")
    private Integer idleUnloadMinutes;

    @Column(name = "pinned", nullable = false)
    private boolean pinned = false;

    protected ProjectWorld() { }

    public ProjectWorld(PloceusKey key, String worldName) {
        this.key = key;
        this.worldName = worldName;
        this.displayName = worldName;
    }

    @Override public PloceusKey getKey() { return key; }
    public String getWorldName() { return worldName; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }
    public boolean isUnassigned() { return project == null; }

    @Override public String getDisplayName() { return displayName; }
    @Override public void setDisplayName(String displayName) { this.displayName = displayName; }
    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public Set<Tag> getTags() { return Collections.unmodifiableSet(tags); }
    @Override public void addTag(Tag tag) { tags.add(tag); }
    @Override public void removeTag(Tag tag) { tags.remove(tag); }

    @Override public List<Author> getAuthors() { return Collections.unmodifiableList(authors); }
    @Override public void addAuthor(Author author) { authors.add(author); }
    @Override public void removeAuthor(UUID playerUuid) {
        authors.removeIf(author -> author.getPlayerUuid().equals(playerUuid));
    }

    public Instant getLastVisitedAt() { return lastVisitedAt; }
    public void setLastVisitedAt(Instant lastVisitedAt) { this.lastVisitedAt = lastVisitedAt; }

    public Integer getIdleUnloadMinutes() { return idleUnloadMinutes; }
    public void setIdleUnloadMinutes(Integer idleUnloadMinutes) { this.idleUnloadMinutes = idleUnloadMinutes; }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
}
