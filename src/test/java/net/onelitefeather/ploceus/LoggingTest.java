package net.onelitefeather.ploceus;

import net.onelitefeather.ploceus.command.CommandRegistrar;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.SchedulerAdapter;
import net.onelitefeather.ploceus.service.VisitTracker;
import net.onelitefeather.ploceus.service.WorldCreationService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Every class this project migrated off `plugin.getLogger()`/`java.util.logging.Logger`
/// declares its own `private static final Logger log = LoggerFactory.getLogger(ThisClass.class)`
/// - the conventional per-class SLF4J shape, chosen over threading a logger through
/// constructors (see the naming discussion in the observability report).
///
/// This asserts the field is 1) actually an `org.slf4j.Logger`, so `java.util.logging.Logger`
/// cannot sneak back in under the same field name without this test noticing, and 2) never
/// null - `LoggerFactory.getLogger(...)` only returns null if the SLF4J binding on the
/// classpath is broken, which is exactly the kind of thing that is invisible until a server
/// actually tries to log something.
class LoggingTest {

    /// Every main-source class known to declare a per-class SLF4J logger. `ArchitectureTest
    /// .loggingGoesThroughSlf4j` is the complementary guarantee - that nothing depends on
    /// `java.util.logging` at all - so this list only needs to cover the classes that log
    /// something, not every class in the project.
    private static final List<Class<?>> LOGGING_CLASSES = List.of(
            PloceusPlugin.class,
            SchedulerAdapter.class,
            CommandRegistrar.class,
            MultiverseAdapter.class,
            VisitTracker.class,
            WorldCreationService.class);

    @TestFactory
    Stream<DynamicTest> everyLoggerFieldIsAUsableSlf4jLogger() {
        return LOGGING_CLASSES.stream().map(type -> DynamicTest.dynamicTest(
                type.getSimpleName() + " declares a usable logger", () -> {
                    Field field = findLogField(type);
                    field.setAccessible(true);
                    assertThat(field.getType()).as("%s.log must be an org.slf4j.Logger", type.getSimpleName())
                            .isEqualTo(Logger.class);
                    Object value = field.get(null);
                    assertThat(value).as("%s.log must never be null", type.getSimpleName()).isNotNull();
                }));
    }

    private static Field findLogField(Class<?> type) throws NoSuchFieldException {
        Field field = type.getDeclaredField("log");
        assertThat(Modifier.isStatic(field.getModifiers()))
                .as("%s.log must be static", type.getSimpleName()).isTrue();
        return field;
    }
}
