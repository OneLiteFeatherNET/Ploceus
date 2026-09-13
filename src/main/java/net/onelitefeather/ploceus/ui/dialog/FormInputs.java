package net.onelitefeather.ploceus.ui.dialog;

import net.onelitefeather.ploceus.domain.OperationResult;
import net.onelitefeather.ploceus.domain.PloceusKey;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/// Parsing and validation for dialog text-input fields. Validation goes through
/// `PloceusKey`/Adventure's own key rules, not a hand-rolled regex.
public final class FormInputs {

    private FormInputs() { }

    public static List<PloceusKey> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(PloceusKey::isValidValue)
                .map(PloceusKey::of)
                .distinct()
                .toList();
    }

    public static OperationResult<PloceusKey> validateKey(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        return PloceusKey.isValidValue(trimmed)
                ? OperationResult.success(PloceusKey.of(trimmed))
                : OperationResult.failure("form.invalidKey", trimmed);
    }
}
