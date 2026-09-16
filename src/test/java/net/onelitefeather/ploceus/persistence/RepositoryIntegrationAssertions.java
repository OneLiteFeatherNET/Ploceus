package net.onelitefeather.ploceus.persistence;

import net.onelitefeather.ploceus.domain.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// Shared assertions for {@link H2RepositoryIntegrationTest} and
/// {@link PostgresRepositoryIntegrationTest}. Each concrete test class owns its own
/// {@code SessionFactory} and repositories (built against a different database) and simply
/// delegates its `@Test` methods here, so the same five assertions run against both H2 and
/// PostgreSQL without sharing static state through a base class.
final class RepositoryIntegrationAssertions {

    private RepositoryIntegrationAssertions() { }

    static void savesAndReadsBackAProjectWithTagsAndAuthors(ProjectRepository projects, TagRepository tags) {
        Project project = new Project(PloceusKey.of("elytrarace"), "Elytra Race");
        project.setDescription("Race tracks");
        project.addTag(tags.findOrCreate(PloceusKey.of("race")));
        project.addAuthor(new Author(UUID.randomUUID(), "TheMeinerLP", "Lead"));
        projects.save(project);

        Project loaded = projects.findByKey(PloceusKey.of("elytrarace")).orElseThrow();
        assertThat(loaded.getDisplayName()).isEqualTo("Elytra Race");
        assertThat(loaded.getTags()).hasSize(1);
        assertThat(loaded.getAuthors()).hasSize(1);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    static void aWorldWithoutAProjectIsFoundAsUnassigned(WorldRepository worlds) {
        worlds.save(new ProjectWorld(PloceusKey.fromWorldName("BikiniRusska"), "BikiniRusska"));
        assertThat(worlds.findUnassigned())
                .extracting(ProjectWorld::getWorldName)
                .contains("BikiniRusska");
    }

    static void searchMatchesOnTag(ProjectRepository projects, TagRepository tags) {
        Project project = new Project(PloceusKey.of("winterlobby"), "Winter Lobby");
        project.addTag(tags.findOrCreate(PloceusKey.of("winter")));
        projects.save(project);

        assertThat(projects.search("winter"))
                .extracting(p -> p.getKey().value())
                .contains("winterlobby");
    }

    static void bulkVisitUpdateWritesEveryEntry(WorldRepository worlds) {
        PloceusKey key = PloceusKey.fromWorldName("visit-test");
        worlds.save(new ProjectWorld(key, "visit-test"));
        Instant now = Instant.now();

        worlds.updateLastVisited(Map.of(key, now));

        assertThat(worlds.findByKey(key).orElseThrow().getLastVisitedAt())
                .isCloseTo(now, within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    /// The regression test for the `LazyInitializationException` that took out
    /// `/ploceus project list` and search in two of three channels. `Project` used to carry
    /// a LAZY `@OneToMany` collection of worlds, and the UI called `getWorlds().size()` on
    /// an entity whose session had already been closed by `fromSession`. Reading the count
    /// here — after the repository returned and the session is gone — is exactly the access
    /// that used to throw.
    static void aProjectCarriesItsWorldCountAfterTheSessionClosed(ProjectRepository projects,
                                                                  WorldRepository worlds) {
        Project project = new Project(PloceusKey.of("counted"), "Counted");
        projects.save(project);
        Project stored = projects.findByKey(PloceusKey.of("counted")).orElseThrow();
        ProjectWorld first = new ProjectWorld(PloceusKey.fromWorldName("counted-one"), "counted-one");
        first.setProject(stored);
        worlds.save(first);
        ProjectWorld second = new ProjectWorld(PloceusKey.fromWorldName("counted-two"), "counted-two");
        second.setProject(stored);
        worlds.save(second);

        assertThat(projects.findByKey(PloceusKey.of("counted")).orElseThrow().getWorldCount())
                .isEqualTo(2);
        assertThat(projects.findAll())
                .filteredOn(p -> p.getKey().value().equals("counted"))
                .first()
                .extracting(Project::getWorldCount)
                .isEqualTo(2);
        assertThat(projects.search("Counted"))
                .filteredOn(p -> p.getKey().value().equals("counted"))
                .first()
                .extracting(Project::getWorldCount)
                .isEqualTo(2);
    }

    static void auditEntriesComeBackNewestFirst(AuditRepository audit) {
        PloceusKey target = PloceusKey.of("audited");
        audit.append(new AuditEntry(AuditEntry.TargetType.PROJECT, target, UUID.randomUUID(),
                "TheMeinerLP", AuditAction.PROJECT_CREATED, null, null, "Audited"));
        audit.append(new AuditEntry(AuditEntry.TargetType.PROJECT, target, UUID.randomUUID(),
                "TheMeinerLP", AuditAction.PROJECT_UPDATED, "displayName", "Audited", "Renamed"));

        assertThat(audit.findByTarget(target, 10, 0))
                .extracting(AuditEntry::getAction)
                .containsExactly(AuditAction.PROJECT_UPDATED, AuditAction.PROJECT_CREATED);
    }
}
