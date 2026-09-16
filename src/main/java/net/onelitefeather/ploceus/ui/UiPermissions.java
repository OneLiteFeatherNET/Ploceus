package net.onelitefeather.ploceus.ui;

import java.util.function.Predicate;

/// Enforces a permission the instant an action actually fires — the check that matters for
/// any client-driven UI callback, whether it is a Paper dialog's custom-click action or an
/// inventory click handler. Both are fired by a plain client packet naming (or locating) a
/// registered callback; a modified client can send that packet without ever having been
/// shown the button/item that would normally trigger it, so hiding it at render time is not,
/// by itself, a control.
///
/// Lives in the channel-neutral `ui` package (not `ui.dialog`) because it now has three
/// consumers — dialog, inventory and (potentially) chat — and ArchUnit's
/// `dialogChannelIsSelfContained`/`inventoryChannelIsSelfContained`/`chatChannelIsSelfContained`
/// rules forbid the channel packages from depending on each other, so a single shared
/// implementation can only live above them.
///
/// Deliberately decoupled from `Player`/`Dialog`/`GuiItem`: it depends only on a
/// `Predicate<String>` (in practice `player::hasPermission`) and a plain `Runnable` denial
/// callback, so the enforcement logic itself is unit-testable without a running server.
public final class UiPermissions {

    /// The floor for every channel. Not just another node: the dialog channel cannot build
    /// a dialog at all without it, because every navigation button it has — back, exit,
    /// confirm, submit — is created through a factory that (correctly) returns no button
    /// for a player who lacks it, while Paper's `DialogType` factories reject a null button.
    public static final String BASE = "ploceus.use";

    private UiPermissions() { }

    /// Returns whether `permission` is held; runs `onDenied` and returns `false` otherwise.
    public static boolean permitted(Predicate<String> hasPermission, String permission, Runnable onDenied) {
        if (hasPermission.test(permission)) {
            return true;
        }
        onDenied.run();
        return false;
    }

    /// Wraps `action` so `permission` is re-checked at the moment the returned `Runnable`
    /// is actually invoked, not just when the button/item that offers it was rendered.
    public static Runnable guarded(Predicate<String> hasPermission, String permission,
                                   Runnable onDenied, Runnable action) {
        return () -> {
            if (permitted(hasPermission, permission, onDenied)) {
                action.run();
            }
        };
    }
}
