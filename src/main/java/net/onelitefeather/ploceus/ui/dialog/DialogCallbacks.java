package net.onelitefeather.ploceus.ui.dialog;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.onelitefeather.ploceus.ui.Messages;
import net.onelitefeather.ploceus.ui.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

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

    public static List<ActionButton> paginationButtons(Page<?> page, IntConsumer onPage) {
        List<ActionButton> buttons = new ArrayList<>(2);
        if (page.hasPrevious()) {
            buttons.add(button(Messages.get("page.previous"), null,
                    () -> onPage.accept(page.pageNumber() - 1)));
        }
        if (page.hasNext()) {
            buttons.add(button(Messages.get("page.next"), null,
                    () -> onPage.accept(page.pageNumber() + 1)));
        }
        return buttons;
    }

    /// A button whose click runs `action`. Dialogs are rebuilt per player, so the callback
    /// closes over exactly that player's state.
    public static ActionButton button(Component label, Component tooltip, Runnable action) {
        ActionButton.Builder builder = ActionButton.builder(label)
                .action(DialogAction.customClick(
                        (view, audience) -> action.run(),
                        ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build()));
        if (tooltip != null) {
            builder = builder.tooltip(tooltip);
        }
        return builder.build();
    }
}
