package net.onelitefeather.ploceus.ui.dialog;

import net.onelitefeather.ploceus.domain.OperationResult;
import net.onelitefeather.ploceus.domain.PloceusKey;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FormInputsTest {

    @Test
    void parsesACommaSeparatedTagList() {
        assertThat(FormInputs.parseTags("winter, Race , build"))
                .containsExactly(PloceusKey.of("winter"), PloceusKey.of("race"), PloceusKey.of("build"));
    }

    @Test
    void skipsBlankAndUnusableEntries() {
        assertThat(FormInputs.parseTags("winter,, ,race")).hasSize(2);
    }

    @Test
    void anEmptyInputYieldsNoTags() {
        assertThat(FormInputs.parseTags("")).isEmpty();
        assertThat(FormInputs.parseTags(null)).isEmpty();
    }

    @Test
    void validateKeyAcceptsALegalValue() {
        assertThat(FormInputs.validateKey("elytrarace").isSuccess()).isTrue();
    }

    @Test
    void validateKeyRejectsSpacesWithTheFormMessage() {
        OperationResult<PloceusKey> result = FormInputs.validateKey("Elytra Race");
        assertThat(((OperationResult.Failure<PloceusKey>) result).messageKey())
                .isEqualTo("form.invalidKey");
    }
}
