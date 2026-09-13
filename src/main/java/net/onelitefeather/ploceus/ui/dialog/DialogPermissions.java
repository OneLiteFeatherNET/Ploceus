package net.onelitefeather.ploceus.ui.dialog;

import java.util.function.Predicate;

/// Enforces a permission the instant an action actually fires — the check that matters for
/// dialog callbacks, because they are client-driven custom-click payloads. A modified
/// client can send the packet for a registered callback id without ever having been shown
/// the button that would normally trigger it, so hiding a button at render time is not, by
/// itself, a control.
///
/// Deliberately decoupled from `Player`/`Dialog`: it depends only on a `Predicate<String>`
/// (in practice `player::hasPermission`) and a plain `Runnable` denial callback, so the
/// enforcement logic itself is unit-testable without a running server — building an actual
/// `ActionButton`/`Dialog` requires Paper's live registry, but this class needs none of it.
public final class DialogPermissions {

    private DialogPermissions() { }

    /// Returns whether `permission` is held; runs `onDenied` and returns `false` otherwise.
    public static boolean permitted(Predicate<String> hasPermission, String permission, Runnable onDenied) {
        if (hasPermission.test(permission)) {
            return true;
        }
        onDenied.run();
        return false;
    }

    /// Wraps `action` so `permission` is re-checked at the moment the returned `Runnable`
    /// is actually invoked, not just when the button that offers it was rendered.
    public static Runnable guarded(Predicate<String> hasPermission, String permission,
                                   Runnable onDenied, Runnable action) {
        return () -> {
            if (permitted(hasPermission, permission, onDenied)) {
                action.run();
            }
        };
    }
}
