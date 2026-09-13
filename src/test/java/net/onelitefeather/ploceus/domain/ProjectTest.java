package net.onelitefeather.ploceus.domain;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ProjectTest {

    @Test
    void tagsAndAuthorsAreManagedThroughTheSharedInterfaces() {
        Project project = new Project(PloceusKey.of("elytrarace"), "Elytra Race");
        Tag winter = new Tag(PloceusKey.of("winter"));
        UUID builder = UUID.randomUUID();

        project.addTag(winter);
        project.addAuthor(new Author(builder, "TheMeinerLP", "Terrain"));

        assertThat(project.getTags()).containsExactly(winter);
        assertThat(project.getAuthors()).hasSize(1);

        project.removeTag(winter);
        project.removeAuthor(builder);

        assertThat(project.getTags()).isEmpty();
        assertThat(project.getAuthors()).isEmpty();
    }

    @Test
    void addingTheSameTagTwiceIsIdempotent() {
        Project project = new Project(PloceusKey.of("elytrarace"), "Elytra Race");
        Tag winter = new Tag(PloceusKey.of("winter"));
        project.addTag(winter);
        project.addTag(winter);
        assertThat(project.getTags()).hasSize(1);
    }

    @Test
    void aNewProjectStartsInPlanning() {
        assertThat(new Project(PloceusKey.of("x"), "X").getStatus()).isEqualTo(ProjectStatus.PLANNING);
    }

    @Test
    void worldWithoutAProjectIsUnassigned() {
        ProjectWorld world = new ProjectWorld(PloceusKey.fromWorldName("BikiniRusska"), "BikiniRusska");
        assertThat(world.getProject()).isNull();
        assertThat(world.isUnassigned()).isTrue();
    }
}
