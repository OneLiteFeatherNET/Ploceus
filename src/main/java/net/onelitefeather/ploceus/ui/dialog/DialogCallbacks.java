package net.onelitefeather.ploceus.ui.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.onelitefeather.ploceus.ui.Messages;
import net.onelitefeather.ploceus.ui.Page;
import net.onelitefeather.ploceus.ui.UiPermissions;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/// Every dialog button `DialogUi` shows is built here, and only here. `button(...)` makes
/// `player` and `permission` mandatory, and applies both halves of the permission gate
/// itself:
///
/// 1. **Render-time**: if `player` lacks `permission`, `button` returns `null` — no
///    `ActionButton` is built at all, so there is no registered callback for a forged
///    client to replay from this render.
/// 2. **Execute-time**: if `player` does have `permission`, the returned button's action is
///    wrapped in {@link UiPermissions#guarded}, which re-checks the same permission the
///    instant the callback actually fires. This is what still matters even though the
///    button was legitimately rendered: dialog buttons are client-driven custom-click
///    payloads, and a previously-granted callback id can outlive the permission that
///    justified rendering it (e.g. an admin revokes a permission after the dialog was
///    already shown).
///
/// There is deliberately no overload that skips either half. Buttons with no real privilege
/// requirement (close, back, pagination) pass `ploceus.use` like every other button — that
/// keeps this factory the *only* way to construct a button that can reach a service, so an
/// unguarded callback is not just discouraged but unwritable.
public final class DialogCallbacks {

    private DialogCallbacks() { }

    /// Pure: which page numbers a pagination row should offer, in display order.
    public static List<Integer> paginationTargets(Page<?> page) {
        List<Integer> targets = new ArrayList<>(2);
        if (page.hasPrevious()) {
            targets.add(page.pageNumber() - 1);
        }
        if (page.hasNext()) {
            targets.add(page.pageNumber() + 1);
        }
        return List.copyOf(targets);
    }

    /// Pagination buttons go through the same mandatory-permission factory as everything
    /// else; `permission` is normally `ploceus.use` (reaching the list already proved the
    /// player may see it), passed explicitly by the caller rather than assumed here.
    public static List<ActionButton> paginationButtons(Player player, String permission, Runnable onDenied,
                                                        Page<?> page, IntConsumer onPage) {
        List<ActionButton> buttons = new ArrayList<>(2);
        if (page.hasPrevious()) {
            addIfPresent(buttons, button(player, permission, Messages.get("page.previous"), null,
                    onDenied, () -> onPage.accept(page.pageNumber() - 1)));
        }
        if (page.hasNext()) {
            addIfPresent(buttons, button(player, permission, Messages.get("page.next"), null,
                    onDenied, () -> onPage.accept(page.pageNumber() + 1)));
        }
        return List.copyOf(buttons);
    }

    private static void addIfPresent(List<ActionButton> buttons, ActionButton button) {
        if (button != null) {
            buttons.add(button);
        }
    }

    /// The only way to build a dialog button. Returns `null` when `player` lacks
    /// `permission` (render-time half); otherwise returns a button whose action is
    /// re-checked against the same permission the instant it fires (execute-time half, via
    /// {@link UiPermissions#guarded}). `onDenied` runs only on the execute-time path —
    /// the render-time path has nothing to show a denial for, since nothing was rendered.
    public static ActionButton button(Player player, String permission, Component label, Component tooltip,
                                      Runnable onDenied, Runnable action) {
        if (!player.hasPermission(permission)) {
            return null;
        }
        Runnable guardedAction = UiPermissions.guarded(player::hasPermission, permission, onDenied, action);
        ActionButton.Builder builder = ActionButton.builder(label)
                .action(DialogAction.customClick(
                        (view, audience) -> guardedAction.run(),
                        ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build()));
        if (tooltip != null) {
            builder = builder.tooltip(tooltip);
        }
        return builder.build();
    }

    /// Convenience for the common case of no tooltip.
    public static ActionButton button(Player player, String permission, Component label,
                                      Runnable onDenied, Runnable action) {
        return button(player, permission, label, null, onDenied, action);
    }
}
