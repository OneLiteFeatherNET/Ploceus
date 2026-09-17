package net.onelitefeather.ploceus.ui;

/// Every `/ploceus …` string a channel puts in front of a player — as a click-to-run link,
/// as a chat suggestion, or as the fallback for a screen a channel cannot render — is built
/// here, and only here.
///
/// It exists because three of those affordances pointed at commands that did not exist, and
/// a fourth pointed at the right command with the wrong page number, and nothing could
/// notice: an `ClickEvent.runCommand("/ploceus world list " + key + " " + page)` is just a
/// string, and no test was in a position to compare it against the command tree.
/// `CommandLinkTest` now parses every method below against `PloceusCommands`' real
/// `@Command` annotations, so a link that would not parse fails the build.
///
/// ## Page numbers
///
/// Commands take **1-based** page numbers (`PloceusCommands` does `Math.max(0, page - 1)`),
/// while {@link Page#pageNumber()} is 0-based. The `…Page` methods below take the 1-based
/// number a player would type; {@link #nextPage} and {@link #previousPage} convert from a
/// `Page` so no caller has to remember which convention it is holding. "Next" on page 1 used
/// to re-render page 1 precisely because a 0-based number was written into a 1-based command.
public final class CommandLinks {

    private static final String ROOT = "/ploceus";

    private CommandLinks() { }

    public static int nextPage(Page<?> page) {
        return page.pageNumber() + 2;
    }

    public static int previousPage(Page<?> page) {
        return page.pageNumber();
    }

    public static String mainMenu() {
        return ROOT;
    }

    public static String projectList() {
        return ROOT + " project list";
    }

    public static String projectList(int displayPage) {
        return projectList() + " " + displayPage;
    }

    public static String projectInfo(String projectKey) {
        return ROOT + " project info " + projectKey;
    }

    public static String unassignedWorlds() {
        return ROOT + " world list-unassigned";
    }

    public static String unassignedWorlds(int displayPage) {
        return unassignedWorlds() + " " + displayPage;
    }

    public static String worldList(String projectKey, int displayPage) {
        return ROOT + " world list " + projectKey + " " + displayPage;
    }

    public static String audit(String targetKey, int displayPage) {
        return ROOT + " audit " + targetKey + " " + displayPage;
    }

    public static String teleport(String worldKey) {
        return ROOT + " world tp " + worldKey;
    }

    public static String createWorld(String projectKey, String worldName) {
        return ROOT + " world create " + projectKey + " " + worldName;
    }

    public static String potentialWorlds() {
        return ROOT + " world potential";
    }

    public static String potentialWorlds(int displayPage) {
        return potentialWorlds() + " " + displayPage;
    }

    public static String importWorld(String folderName) {
        return ROOT + " world import " + folderName;
    }

    // --- deliberately incomplete: suggestions the player finishes typing themselves ---

    /// Suggestion prefix, not a runnable command: the player types the search term.
    public static String searchPrompt() {
        return ROOT + " search ";
    }

    /// Suggestion prefix: the player types the key and the display name.
    public static String createProjectPrompt() {
        return ROOT + " project create ";
    }

    /// Suggestion prefix: the player types the new display name.
    public static String renameProjectPrompt(String projectKey) {
        return ROOT + " project rename " + projectKey + " ";
    }

    /// Suggestion prefix: the player types the new description.
    public static String describeProjectPrompt(String projectKey) {
        return ROOT + " project describe " + projectKey + " ";
    }
}
