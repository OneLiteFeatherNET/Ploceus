package net.onelitefeather.ploceus.ui;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Structural contract: every channel must implement every screen, and none may quietly
/// inherit a default or satisfy the interface with a stub that throws at runtime.
/// Behavioural equivalence is verified in game; this catches the far more common failure of
/// a channel drifting out of sync when a screen is added, or being left in the intermediate
/// state this project actually went through during development, where a channel compiled
/// against `ProjectUi` but a screen still threw `UnsupportedOperationException`.
class ProjectUiContractTest {

    private static final List<Class<?>> CHANNELS = List.of(
            net.onelitefeather.ploceus.ui.dialog.DialogUi.class,
            net.onelitefeather.ploceus.ui.inventory.InventoryUi.class,
            net.onelitefeather.ploceus.ui.chat.ChatUi.class);

    @TestFactory
    Stream<DynamicTest> everyChannelImplementsEveryScreen() {
        return CHANNELS.stream().flatMap(channel ->
                Arrays.stream(ProjectUi.class.getDeclaredMethods()).map(screen ->
                        DynamicTest.dynamicTest(
                                channel.getSimpleName() + " implements " + screen.getName(),
                                () -> assertThat(declares(channel, screen))
                                        .as("%s must override %s rather than inherit it",
                                                channel.getSimpleName(), screen.getName())
                                        .isTrue())));
    }

    /// Checks each channel's own compiled class file for a reference to
    /// `UnsupportedOperationException`, without needing a live Bukkit `Player` to actually
    /// invoke every screen. A `throw new UnsupportedOperationException(...)` anywhere in a
    /// class compiles to a `CONSTANT_Class` entry in that class's constant pool pointing at a
    /// `CONSTANT_Utf8` entry holding the literal `"java/lang/UnsupportedOperationException"` -
    /// so scanning the raw bytecode for that UTF-8 string reliably detects the stub, in any
    /// method, without executing the class or needing an extra ASM dependency.
    ///
    /// This does not see into anonymous classes or lambda bodies that the compiler emits as
    /// separate `.class` files (e.g. `DialogUi$1.class`); none of the three channels currently
    /// use `Runnable` stubs of that shape for a screen body, so that gap is accepted rather
    /// than chased with a full classpath scan.
    @TestFactory
    Stream<DynamicTest> noChannelThrowsUnsupportedOperationException() {
        return CHANNELS.stream().map(channel -> DynamicTest.dynamicTest(
                channel.getSimpleName() + " has no UnsupportedOperationException stubs",
                () -> assertThat(referencesUnsupportedOperationException(channel))
                        .as("%s must not reference UnsupportedOperationException in its own bytecode; a channel "
                                        + "that satisfies ProjectUi but throws from a screen body looks implemented "
                                        + "until a player actually opens it",
                                channel.getSimpleName())
                        .isFalse()));
    }

    private boolean declares(Class<?> channel, Method screen) {
        try {
            channel.getDeclaredMethod(screen.getName(), screen.getParameterTypes());
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    private boolean referencesUnsupportedOperationException(Class<?> channel) {
        String resource = channel.getName().replace('.', '/') + ".class";
        try (InputStream in = channel.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Could not locate compiled class file for " + channel.getName());
            }
            byte[] bytecode = in.readAllBytes();
            return containsUtf8Constant(bytecode, "java/lang/UnsupportedOperationException");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private boolean containsUtf8Constant(byte[] haystack, String needle) {
        byte[] pattern = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= haystack.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (haystack[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
