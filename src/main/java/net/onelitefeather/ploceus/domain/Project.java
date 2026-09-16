package net.onelitefeather.ploceus.domain;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "ploceus_project")
public class Project extends AuditableEntity implements Taggable, Authored, Describable {

    @Id
    @Column(name = "project_key", length = 255)
    private PloceusKey key;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "description", length = 2000)
    private String description = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ProjectStatus status = ProjectStatus.PLANNING;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "ploceus_project_tag",
            joinColumns = @JoinColumn(name = "project_key"),
            inverseJoinColumns = @JoinColumn(name = "tag_key"))
    private Set<Tag> tags = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ploceus_project_author", joinColumns = @JoinColumn(name = "project_key"))
    private List<Author> authors = new ArrayList<>();

    @Embedded
    private WorldDefaults worldDefaults;

    /// How many worlds belong to this project. Deliberately **not** a mapped
    /// `@OneToMany` association: every repository read hands the caller a detached entity
    /// (`SessionFactory.fromSession` closes the session before returning), so a lazy
    /// collection here threw `LazyInitializationException` in every UI channel that only
    /// wanted the size. A projection populated by `ProjectRepository` costs one grouped
    /// count query for a whole page instead of loading every world to display a number.
    ///
    /// Zero on an instance that was never read back through the repository — a freshly
    /// constructed project has no worlds yet, which is the same answer.
    @Transient
    private int worldCount;

    protected Project() { }

    public Project(PloceusKey key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    @Override public PloceusKey getKey() { return key; }
    @Override public String getDisplayName() { return displayName; }
    @Override public void setDisplayName(String displayName) { this.displayName = displayName; }
    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus status) { this.status = status; }

    @Override public Set<Tag> getTags() { return Collections.unmodifiableSet(tags); }
    @Override public void addTag(Tag tag) { tags.add(tag); }
    @Override public void removeTag(Tag tag) { tags.remove(tag); }

    @Override public List<Author> getAuthors() { return Collections.unmodifiableList(authors); }
    @Override public void addAuthor(Author author) { authors.add(author); }
    @Override public void removeAuthor(UUID playerUuid) {
        authors.removeIf(author -> author.getPlayerUuid().equals(playerUuid));
    }

    public WorldDefaults getWorldDefaults() { return worldDefaults; }
    public void setWorldDefaults(WorldDefaults worldDefaults) { this.worldDefaults = worldDefaults; }

    public int getWorldCount() { return worldCount; }
    public void setWorldCount(int worldCount) { this.worldCount = worldCount; }
}
