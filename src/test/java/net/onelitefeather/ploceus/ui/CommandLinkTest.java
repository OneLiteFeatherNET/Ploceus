package net.onelitefeather.ploceus.ui;

import net.onelitefeather.ploceus.command.PloceusCommands;
import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotations.ArgumentMode;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.SyntaxFragment;
import org.incendo.cloud.annotations.SyntaxParser;
import org.incendo.cloud.annotations.SyntaxParserImpl;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Parses every command string the UI channels hand to a player against the command tree
/// `PloceusCommands` actually registers, read from its real `@Command` annotations with
/// Cloud's own `SyntaxParserImpl`.
///
/// This is the test that was missing. Three affordances pointed at commands that were never
/// registered — `/ploceus world list <key> <page>` did not exist at all, and both the chat
/// and the inventory channel suggested `/ploceus world create <key> <name>` when the
/// registered command took only `<key>` — and nothing could see it, because a click event
/// carries a plain `String` that no test compared to anything.
class CommandLinkTest {

    private static final SyntaxParser SYNTAX = new SyntaxParserImpl();
    private static final List<Method> COMMANDS = Arrays.stream(PloceusCommands.class.getMethods())
            .filter(method -> method.isAnnotationPresent(Command.class))
            .toList();

    /// Complete commands: a click-to-run link must match a registered command exactly,
    /// consuming every token it carries.
    private static Map<String, String> runnableLinks() {
        Map<String, String> links = new LinkedHashMap<>();
        links.put("mainMenu", CommandLinks.mainMenu());
        links.put("projectList", CommandLinks.projectList());
        links.put("projectList(page)", CommandLinks.projectList(2));
        links.put("projectInfo", CommandLinks.projectInfo("elytrarace"));
        links.put("unassignedWorlds", CommandLinks.unassignedWorlds());
        links.put("unassignedWorlds(page)", CommandLinks.unassignedWorlds(2));
        links.put("worldList", CommandLinks.worldList("elytrarace", 2));
        links.put("audit", CommandLinks.audit("elytrarace", 2));
        links.put("teleport", CommandLinks.teleport("elytrarace-alien"));
        links.put("createWorld", CommandLinks.createWorld("elytrarace", "elytrarace-desert"));
        links.put("potentialWorlds", CommandLinks.potentialWorlds());
        links.put("potentialWorlds(page)", CommandLinks.potentialWorlds(2));
        links.put("importWorld", CommandLinks.importWorld("elytrarace-swamp"));
        return links;
    }

    /// Suggestions the player finishes typing: only the tokens already present have to line
    /// up with the tree, and the trailing space is what tells the player there is more.
    private static Map<String, String> suggestionPrefixes() {
        Map<String, String> links = new LinkedHashMap<>();
        links.put("searchPrompt", CommandLinks.searchPrompt());
        links.put("createProjectPrompt", CommandLinks.createProjectPrompt());
        links.put("renameProjectPrompt", CommandLinks.renameProjectPrompt("elytrarace"));
        links.put("describeProjectPrompt", CommandLinks.describeProjectPrompt("elytrarace"));
        return links;
    }

    @TestFactory
    Stream<DynamicTest> everyRunnableLinkMatchesARegisteredCommand() {
        return runnableLinks().entrySet().stream().map(link -> DynamicTest.dynamicTest(
                link.getKey() + " -> " + link.getValue(),
                () -> assertThat(matches(link.getValue(), false))
                        .as("'%s' matches no registered @Command in PloceusCommands, so clicking it "
                                + "would put an unparseable command in front of the player", link.getValue())
                        .isTrue()));
    }

    @TestFactory
    Stream<DynamicTest> everySuggestionPrefixLeadsIntoARegisteredCommand() {
        return suggestionPrefixes().entrySet().stream().map(link -> DynamicTest.dynamicTest(
                link.getKey() + " -> " + link.getValue(),
                () -> {
                    assertThat(link.getValue()).as("a suggestion is deliberately incomplete and must "
                            + "end in a space so the player can type the rest").endsWith(" ");
                    assertThat(matches(link.getValue(), true))
                            .as("'%s' is not a prefix of any registered @Command in PloceusCommands",
                                    link.getValue())
                            .isTrue();
                }));
    }

    private static boolean matches(String link, boolean asPrefix) {
        List<String> tokens = Arrays.stream(link.trim().substring(1).split("\\s+"))
                .filter(token -> !token.isEmpty())
                .toList();
        return COMMANDS.stream().anyMatch(method -> consumes(method, tokens, asPrefix));
    }

    private static boolean consumes(Method method, List<String> tokens, boolean asPrefix) {
        List<SyntaxFragment> fragments = SYNTAX.parseSyntax(method, method.getAnnotation(Command.class).value());
        int index = 0;
        for (SyntaxFragment fragment : fragments) {
            if (index >= tokens.size()) {
                // Everything left over must be optional, unless we only needed a prefix.
                return asPrefix || fragments.stream().skip(fragments.indexOf(fragment))
                        .allMatch(rest -> rest.argumentMode() == ArgumentMode.OPTIONAL);
            }
            String token = tokens.get(index);
            if (fragment.argumentMode() == ArgumentMode.LITERAL) {
                if (!fragment.major().equals(token) && !fragment.minor().contains(token)) {
                    return false;
                }
                index++;
            } else if (isGreedy(method, fragment.major())) {
                index = tokens.size();
            } else {
                index++;
            }
        }
        return index == tokens.size();
    }

    /// A greedy argument swallows the rest of the input, so it consumes every remaining
    /// token rather than exactly one.
    private static boolean isGreedy(Method method, String argumentName) {
        List<Parameter> parameters = new ArrayList<>(Arrays.asList(method.getParameters()));
        return parameters.stream().anyMatch(parameter ->
                parameter.isAnnotationPresent(Greedy.class)
                        && argumentName.equals(annotatedName(parameter)));
    }

    private static String annotatedName(Parameter parameter) {
        var argument = parameter.getAnnotation(org.incendo.cloud.annotations.Argument.class);
        return argument == null ? parameter.getName() : argument.value();
    }
}
