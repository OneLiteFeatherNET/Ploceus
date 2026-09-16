package net.onelitefeather.ploceus.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.onelitefeather.ploceus.domain.OperationResult;
import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.Project;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Catches an unresolved MiniMessage tag before a player sees one.
///
/// MiniMessage renders a tag it cannot resolve as literal text, so
/// `Messages.get("project.created")` with no placeholders produced the sentence
/// "Project &lt;name&gt; created." — which is exactly what every successful operation used
/// to print, in all three channels. A rendered message that still contains an
/// `&lt;identifier&gt;` is detectable, and that is what these assertions look for.
class FeedbackMessageTest {

    /// Anything that still looks like a MiniMessage tag after rendering. Closing tags and
    /// colour tags never survive `deserialize`, so any match here is an operand nobody
    /// supplied.
    private static final Pattern UNRESOLVED_TAG = Pattern.compile("<[a-zA-Z][a-zA-Z0-9_.:-]*>");

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /// Every `successKey` passed to `ProjectUi.feedback` anywhere in the project, paired
    /// with the kind of value the operation behind it returns.
    private static Map<String, OperationResult.Success<?>> successCases() {
        Project project = new Project(PloceusKey.of("elytrarace"), "Elytra Race");
        ProjectWorld assigned = new ProjectWorld(PloceusKey.of("elytrarace-desert"), "elytrarace-desert");
        assigned.setDisplayName("Desert Track");
        assigned.setProject(project);
        ProjectWorld unassigned = new ProjectWorld(PloceusKey.of("bak-survival"), "bak_Survival");

        Map<String, OperationResult.Success<?>> cases = new LinkedHashMap<>();
        cases.put("project.created", new OperationResult.Success<>(project));
        cases.put("project.updated", new OperationResult.Success<>(project));
        cases.put("project.deleted", new OperationResult.Success<>(project));
        cases.put("world.assigned", new OperationResult.Success<>(assigned));
        cases.put("world.unassigned", new OperationResult.Success<>(unassigned));
        cases.put("world.renamed", new OperationResult.Success<>(assigned));
        cases.put("world.created", new OperationResult.Success<>(assigned));
        cases.put("world.settingsUpdated", new OperationResult.Success<>(assigned));
        return cases;
    }

    @TestFactory
    Stream<DynamicTest> noSuccessMessageReachesThePlayerWithAnUnresolvedTag() {
        return successCases().entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    Component rendered = Messages.get(entry.getKey(),
                            ProjectUi.successOperands(entry.getValue()));
                    String text = PLAIN.serialize(rendered);
                    assertThat(UNRESOLVED_TAG.matcher(text).find())
                            .as("'%s' rendered as \"%s\"; an unresolved MiniMessage tag is shown to "
                                    + "the player verbatim", entry.getKey(), text)
                            .isFalse();
                }));
    }

    /// The operand has to be *substituted*, not merely absent: a message that dropped the
    /// name would pass the tag check above while still telling the player nothing.
    @TestFactory
    Stream<DynamicTest> everySuccessMessageThatNamesSomethingNamesTheRightThing() {
        Map<String, String> expectedOperand = Map.of(
                "project.created", "Elytra Race",
                "project.updated", "Elytra Race",
                "project.deleted", "Elytra Race",
                "world.assigned", "Desert Track",
                "world.unassigned", "bak_Survival",
                "world.renamed", "Desert Track",
                "world.created", "Desert Track");
        Map<String, OperationResult.Success<?>> cases = successCases();
        return expectedOperand.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey() + " names " + entry.getValue(),
                () -> assertThat(PLAIN.serialize(Messages.get(entry.getKey(),
                        ProjectUi.successOperands(cases.get(entry.getKey())))))
                        .contains(entry.getValue())));
    }

    /// `world.assigned` is the one success message with a second operand.
    @org.junit.jupiter.api.Test
    void anAssignmentNamesBothTheWorldAndTheProject() {
        String text = PLAIN.serialize(Messages.get("world.assigned",
                ProjectUi.successOperands(successCases().get("world.assigned"))));
        assertThat(text).contains("Desert Track").contains("Elytra Race");
    }

    /// Structural half: a channel that renders `successKey` without
    /// {@link ProjectUi#successOperands} is back to the original bug, and the only way to
    /// see that from here without a live server is the channel's own constant pool — the
    /// same technique `ProjectUiContractTest` uses to spot stubbed screens.
    @TestFactory
    Stream<DynamicTest> everyChannelRendersSuccessMessagesWithOperands() {
        return Stream.of(
                        net.onelitefeather.ploceus.ui.dialog.DialogUi.class,
                        net.onelitefeather.ploceus.ui.inventory.InventoryUi.class,
                        net.onelitefeather.ploceus.ui.chat.ChatUi.class)
                .map(channel -> DynamicTest.dynamicTest(
                        channel.getSimpleName() + " calls successOperands",
                        () -> assertThat(referencesSuccessOperands(channel))
                                .as("%s renders its success message without ProjectUi.successOperands, "
                                        + "so every <name> in it reaches the player as literal text",
                                        channel.getSimpleName())
                                .isTrue()));
    }

    private static boolean referencesSuccessOperands(Class<?> channel) {
        String resource = channel.getName().replace('.', '/') + ".class";
        try (InputStream in = channel.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("No compiled class file for " + channel.getName());
            }
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).contains("successOperands");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
