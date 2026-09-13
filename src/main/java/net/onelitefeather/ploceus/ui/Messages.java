package net.onelitefeather.ploceus.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Map;

public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final Map<String, String> TEMPLATES = Map.ofEntries(
            Map.entry("prefix", "<gradient:#f2c14e:#f0a202>Ploceus</gradient> <dark_gray>»</dark_gray> "),
            Map.entry("menu.title", "<gold>Ploceus</gold>"),
            Map.entry("menu.projects", "<yellow>Projects</yellow>"),
            Map.entry("menu.unassigned", "<yellow>Unassigned worlds</yellow>"),
            Map.entry("menu.search", "<yellow>Search</yellow>"),
            Map.entry("project.list.title", "<gold>Projects</gold> <gray>(page <white><page></white>/<white><pages></white>)</gray>"),
            Map.entry("project.entry", "<yellow><name></yellow> <gray>— <worlds> worlds, <tags></gray>"),
            Map.entry("project.created", "<green>Project <white><name></white> created.</green>"),
            Map.entry("project.updated", "<green>Project <white><name></white> updated.</green>"),
            Map.entry("project.deleted", "<green>Project <white><name></white> deleted.</green>"),
            Map.entry("project.exists", "<red>A project with the key <white><detail></white> already exists.</red>"),
            Map.entry("project.unknown", "<red>No project with the key <white><detail></white>.</red>"),
            Map.entry("world.entry", "<yellow><name></yellow> <gray>(<state>)</gray>"),
            Map.entry("world.state.loaded", "<green>loaded</green>"),
            Map.entry("world.state.unloaded", "<dark_gray>unloaded</dark_gray>"),
            Map.entry("world.assigned", "<green>World <white><name></white> assigned to <white><project></white>.</green>"),
            Map.entry("world.unassigned", "<green>World <white><name></white> is now unassigned.</green>"),
            Map.entry("world.created", "<green>World <white><name></white> created.</green>"),
            Map.entry("world.renamed", "<green>Display name changed to <white><name></white>.</green>"),
            Map.entry("world.settingsUpdated", "<green>World settings updated.</green>"),
            Map.entry("world.unknown", "<red>No world with the key <white><detail></white>.</red>"),
            Map.entry("world.load.failed", "<red>Could not load the world: <white><detail></white></red>"),
            Map.entry("world.unload.failed", "<red>Could not unload the world: <white><detail></white></red>"),
            Map.entry("world.create.failed", "<red>Could not create the world: <white><detail></white></red>"),
            Map.entry("world.alias.failed", "<red>Could not update the Multiverse alias for <white><detail></white>.</red>"),
            Map.entry("world.autoload.failed", "<red>Could not change auto-load for <white><detail></white>.</red>"),
            Map.entry("world.teleporting", "<gray>Loading <white><name></white> and teleporting…</gray>"),
            Map.entry("search.prompt", "<gray>Enter a search term.</gray>"),
            Map.entry("search.empty", "<gray>Nothing found for <white><term></white>.</gray>"),
            Map.entry("search.results", "<gold>Results for <white><term></white></gold>"),
            Map.entry("audit.entry", "<gray><at></gray> <yellow><actor></yellow> <white><action></white> <gray><field>: <old> → <new></gray>"),
            Map.entry("page.previous", "<yellow>« Previous</yellow>"),
            Map.entry("page.next", "<yellow>Next »</yellow>"),
            Map.entry("page.back", "<gray>« Back</gray>"),
            Map.entry("button.confirm", "<green>Confirm</green>"),
            Map.entry("button.cancel", "<red>Cancel</red>"),
            Map.entry("button.reroll", "<aqua>Suggest another name</aqua>"),
            Map.entry("button.advanced", "<gray>Advanced…</gray>"),
            Map.entry("form.key", "<yellow>Key</yellow>"),
            Map.entry("form.displayName", "<yellow>Display name</yellow>"),
            Map.entry("form.description", "<yellow>Description</yellow>"),
            Map.entry("form.tags", "<yellow>Tags (comma separated)</yellow>"),
            Map.entry("form.status", "<yellow>Status</yellow>"),
            Map.entry("form.generator", "<yellow>Generator</yellow>"),
            Map.entry("form.environment", "<yellow>Environment</yellow>"),
            Map.entry("form.structures", "<yellow>Generate structures</yellow>"),
            Map.entry("form.seed", "<yellow>Seed (empty = random)</yellow>"),
            Map.entry("form.invalidKey", "<red><white><detail></white> is not a valid key. Use lowercase letters, digits, '-', '_' and '.'.</red>"),
            Map.entry("ui.changed", "<green>Interface set to <white><channel></white>.</green>"),
            Map.entry("import.done", "<green>Imported <white><imported></white> worlds, <white><known></white> already known.</green>"),
            Map.entry("world.state.neverVisited", "<dark_gray>never visited</dark_gray>"),
            Map.entry("world.state.visited", "<green>known</green>"),
            Map.entry("project.noTags", "<gray>no tags</gray>")
    );

    private Messages() { }

    public static Component get(String key, TagResolver... placeholders) {
        String template = TEMPLATES.get(key);
        if (template == null) {
            return Component.text("Missing message: " + key);
        }
        return MINI.deserialize(template, placeholders);
    }

    /// Prefixed variant for chat output. Dialog and inventory channels use {@link #get}.
    public static Component chat(String key, TagResolver... placeholders) {
        return get("prefix").append(get(key, placeholders));
    }

    public static TagResolver placeholder(String name, String value) {
        return Placeholder.unparsed(name, value == null ? "" : value);
    }

    /// For composing another message's already-rendered `Component` (e.g. a state label
    /// looked up via {@link #get}) into a placeholder, instead of flattening it to a string.
    public static TagResolver component(String name, Component value) {
        return Placeholder.component(name, value);
    }
}
