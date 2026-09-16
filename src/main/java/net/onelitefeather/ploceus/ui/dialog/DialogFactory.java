package net.onelitefeather.ploceus.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;

import java.util.List;

public final class DialogFactory {

    private DialogFactory() { }

    public static Dialog multiAction(Component title, List<ActionButton> buttons,
                                     ActionButton exit, int columns) {
        return multiAction(title, List.of(), buttons, exit, columns);
    }

    /// Overload with a text body: `DialogBase` and `DialogType` are independent, so a
    /// multi-action button grid can still carry a body above it — used for the audit trail,
    /// which needs both free text (the entries) and buttons (pagination, back).
    public static Dialog multiAction(Component title, List<DialogBody> body, List<ActionButton> buttons,
                                     ActionButton exit, int columns) {
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title).body(body).build())
                .type(DialogType.multiAction(buttons).columns(columns).exitAction(exit).build()));
    }

    /// Overload with inputs instead of a body: `DialogBase` and `DialogType` are
    /// independent, so a multi-action button grid can carry form inputs too — used for the
    /// world creation form, which needs more than one action (create, re-roll the suggested
    /// name) alongside its inputs, unlike `notice`'s single submit button.
    public static Dialog multiAction(Component title, List<DialogInput> inputs, List<ActionButton> buttons,
                                     int columns) {
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title).inputs(inputs).build())
                .type(DialogType.multiAction(buttons).columns(columns).build()));
    }

    public static Dialog notice(Component title, List<DialogBody> body,
                                List<DialogInput> inputs, ActionButton submit) {
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title).body(body).inputs(inputs).build())
                .type(DialogType.notice(submit)));
    }

    public static Dialog confirmation(Component title, List<DialogBody> body,
                                      ActionButton yes, ActionButton no) {
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title).body(body).build())
                .type(DialogType.confirmation(yes, no)));
    }
}
