package net.onelitefeather.ploceus.domain;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Every repository read in this project goes through `SessionFactory.fromSession`, which
/// closes the session before the entity reaches the caller. A detached entity can only
/// answer for state that was loaded while the session was open, so **no association on a
/// Ploceus entity may be lazy** — a lazy one does not fail at load time, it fails later, in
/// the UI, on a thread and in a channel that no unit test with plain objects can reach.
///
/// This is the rule the `LazyInitializationException` on `/ploceus project list` broke:
/// `Project.worlds` was `@OneToMany(mappedBy = "project")`, whose JPA default is LAZY, while
/// `tags` and `authors` next to it were explicitly EAGER.
class DetachedEntityTest {

    private static final List<Class<?>> ENTITIES =
            List.of(Project.class, ProjectWorld.class, Tag.class, AuditEntry.class);

    @TestFactory
    Stream<DynamicTest> noEntityAssociationIsLazilyFetched() {
        return ENTITIES.stream()
                .flatMap(entity -> Stream.of(entity.getDeclaredFields())
                        .filter(DetachedEntityTest::isAssociation)
                        .map(field -> DynamicTest.dynamicTest(
                                entity.getSimpleName() + "." + field.getName() + " is EAGER",
                                () -> assertThat(fetchTypeOf(field))
                                        .as("%s.%s is fetched lazily, but every repository read "
                                                        + "detaches its result — the first caller to "
                                                        + "touch it gets LazyInitializationException",
                                                entity.getSimpleName(), field.getName())
                                        .isEqualTo(FetchType.EAGER))));
    }

    @TestFactory
    Stream<DynamicTest> everyEntityIsCoveredByThisTest() {
        return ENTITIES.stream().map(entity -> DynamicTest.dynamicTest(
                entity.getSimpleName() + " is an @Entity",
                () -> assertThat(entity.isAnnotationPresent(Entity.class)).isTrue()));
    }

    private static boolean isAssociation(Field field) {
        return field.isAnnotationPresent(OneToMany.class)
                || field.isAnnotationPresent(ManyToMany.class)
                || field.isAnnotationPresent(ManyToOne.class)
                || field.isAnnotationPresent(OneToOne.class)
                || field.isAnnotationPresent(ElementCollection.class);
    }

    private static FetchType fetchTypeOf(Field field) {
        if (field.isAnnotationPresent(OneToMany.class)) {
            return field.getAnnotation(OneToMany.class).fetch();
        }
        if (field.isAnnotationPresent(ManyToMany.class)) {
            return field.getAnnotation(ManyToMany.class).fetch();
        }
        if (field.isAnnotationPresent(ManyToOne.class)) {
            return field.getAnnotation(ManyToOne.class).fetch();
        }
        if (field.isAnnotationPresent(OneToOne.class)) {
            return field.getAnnotation(OneToOne.class).fetch();
        }
        return field.getAnnotation(ElementCollection.class).fetch();
    }
}
