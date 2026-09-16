package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.PloceusKey;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NameSuggesterTest {

    private final NameSuggester suggester = new NameSuggester();

    @RepeatedTest(25)
    void everySuggestionIsAUsableKeyValue() {
        String suggestion = suggester.suggest();
        assertThat(suggestion).isNotBlank();
        assertThat(PloceusKey.isValidValue(suggestion))
                .as("suggestion '%s' must be a legal key value", suggestion)
                .isTrue();
    }

    @Test
    void suggestUniqueSkipsNamesAlreadyTaken() {
        Set<String> taken = Set.of("a", "b");
        String suggestion = suggester.suggestUnique(taken::contains);
        assertThat(taken).doesNotContain(suggestion);
    }

    @RepeatedTest(10)
    void suggestionsVary() {
        assertThat(suggester.suggest()).isNotEqualTo(suggester.suggest());
    }
}
