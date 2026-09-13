package net.onelitefeather.ploceus.ui.dialog;

import net.onelitefeather.ploceus.ui.Page;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DialogPaginationTest {

    private final List<String> items = List.of("a", "b", "c", "d", "e");

    @Test
    void theFirstPageOffersOnlyNext() {
        List<Integer> requested = new ArrayList<>();
        var buttons = DialogCallbacks.paginationTargets(Page.of(items, 0, 2));
        assertThat(buttons).containsExactly(1);
    }

    @Test
    void aMiddlePageOffersBothDirections() {
        assertThat(DialogCallbacks.paginationTargets(Page.of(items, 1, 2)))
                .containsExactly(0, 2);
    }

    @Test
    void theLastPageOffersOnlyPrevious() {
        assertThat(DialogCallbacks.paginationTargets(Page.of(items, 2, 2)))
                .containsExactly(1);
    }

    @Test
    void aSinglePageOffersNoNavigation() {
        assertThat(DialogCallbacks.paginationTargets(Page.of(items, 0, 10))).isEmpty();
    }
}
