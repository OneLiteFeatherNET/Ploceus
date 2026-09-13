package net.onelitefeather.ploceus.command;

import net.onelitefeather.ploceus.domain.PloceusKey;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.ArgumentMode;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.SyntaxFragment;
import org.incendo.cloud.annotations.SyntaxParser;
import org.incendo.cloud.annotations.SyntaxParserImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Cloud's `@Flag` annotation binds `--name` presence flags to a method *parameter*, not to
/// a token written into the `@Command` syntax string — a plain `--confirm` token in the
/// string (as `PloceusCommands.projectDelete` uses) is parsed as a required *literal*
/// instead. This test reads the real annotation off the real method with Cloud's own
/// `SyntaxParserImpl` (the same parser `AnnotationParser` uses internally) to confirm that
/// literal is present and required, rather than asserting against a copy of the string.
///
/// A full end-to-end assertion — building a `CommandManager`, invoking the parsed command
/// with and without `--confirm`, and checking the second one is rejected — was not
/// attempted: this project has no Bukkit/Paper server test harness (no MockBukkit or
/// equivalent anywhere in `src/test`), and standing one up is out of scope for this task.
/// The claim is instead verified structurally: a `LITERAL` syntax fragment is, by Cloud's
/// command-tree construction, always a mandatory node — there is no "optional literal" in
/// `SyntaxParserImpl` (only `<required>` and `[optional]` *arguments* can be optional) — and
/// `PloceusCommands` registers no other `@Command` overload for "ploceus project delete"
/// that omits it, so no path through the registered command tree reaches
/// `ProjectService.delete` without the caller having typed `--confirm` literally.
class CommandSyntaxTest {

    private static final SyntaxParser PARSER = new SyntaxParserImpl();

    @Test
    void deleteCommandRequiresTheConfirmLiteral() throws NoSuchMethodException {
        Method method = PloceusCommands.class.getMethod("projectDelete", Player.class, PloceusKey.class);
        String syntax = method.getAnnotation(Command.class).value();

        List<SyntaxFragment> fragments = PARSER.parseSyntax(method, syntax);
        SyntaxFragment last = fragments.get(fragments.size() - 1);

        assertThat(last.argumentMode()).isEqualTo(ArgumentMode.LITERAL);
        assertThat(last.major()).isEqualTo("--confirm");
    }

    @Test
    void deleteCommandHasNoOverloadThatOmitsConfirm() {
        long deleteOverloads = java.util.Arrays.stream(PloceusCommands.class.getMethods())
                .filter(method -> method.isAnnotationPresent(Command.class))
                .map(method -> method.getAnnotation(Command.class).value())
                .filter(syntax -> syntax.contains("project delete"))
                .count();

        assertThat(deleteOverloads).isEqualTo(1);
    }
}
