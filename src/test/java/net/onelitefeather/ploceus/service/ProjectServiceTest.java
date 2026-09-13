package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectServiceTest {

    private final Actor actor = new Actor(UUID.randomUUID(), "TheMeinerLP");

    @Test
    void createRejectsADuplicateKey() {
        ProjectService service = InMemoryFixtures.projectService();
        assertThat(service.create(PloceusKey.of("a"), "A", actor).isSuccess()).isTrue();

        OperationResult<Project> second = service.create(PloceusKey.of("a"), "A again", actor);
        assertThat(second).isInstanceOf(OperationResult.Failure.class);
        assertThat(((OperationResult.Failure<Project>) second).messageKey())
                .isEqualTo("project.exists");
    }

    @Test
    void renameWritesAnAuditEntryWithBothValues() {
        ProjectService service = InMemoryFixtures.projectService();
        service.create(PloceusKey.of("a"), "Old name", actor);

        service.rename(PloceusKey.of("a"), "New name", actor);

        assertThat(service.find(PloceusKey.of("a")).orElseThrow().getDisplayName())
                .isEqualTo("New name");
        AuditEntry entry = InMemoryFixtures.lastAudit();
        assertThat(entry.getAction()).isEqualTo(AuditAction.PROJECT_UPDATED);
        assertThat(entry.getField()).isEqualTo("displayName");
        assertThat(entry.getOldValue()).isEqualTo("Old name");
        assertThat(entry.getNewValue()).isEqualTo("New name");
    }

    @Test
    void renamingAMissingProjectFails() {
        ProjectService service = InMemoryFixtures.projectService();
        OperationResult<Project> result = service.rename(PloceusKey.of("ghost"), "x", actor);
        assertThat(((OperationResult.Failure<Project>) result).messageKey()).isEqualTo("project.unknown");
    }

    @Test
    void renamingToTheSameValueWritesNoAuditEntry() {
        ProjectService service = InMemoryFixtures.projectService();
        service.create(PloceusKey.of("a"), "Same", actor);
        int before = InMemoryFixtures.auditCount();

        service.rename(PloceusKey.of("a"), "Same", actor);

        assertThat(InMemoryFixtures.auditCount()).isEqualTo(before);
    }
}
