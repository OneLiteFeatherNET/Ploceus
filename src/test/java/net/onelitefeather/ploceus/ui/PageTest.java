package net.onelitefeather.ploceus.ui;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PageTest {

    private final List<String> items = List.of("a", "b", "c", "d", "e", "f", "g");

    @Test
    void firstPageHasNoPreviousButHasNext() {
        Page<String> page = Page.of(items, 0, 3);
        assertThat(page.items()).containsExactly("a", "b", "c");
        assertThat(page.hasPrevious()).isFalse();
        assertThat(page.hasNext()).isTrue();
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void lastPageIsPartialAndHasNoNext() {
        Page<String> page = Page.of(items, 2, 3);
        assertThat(page.items()).containsExactly("g");
        assertThat(page.hasNext()).isFalse();
        assertThat(page.hasPrevious()).isTrue();
    }

    @Test
    void aPageBeyondTheEndIsEmptyRatherThanThrowing() {
        assertThat(Page.of(items, 99, 3).items()).isEmpty();
    }

    @Test
    void anEmptyListYieldsOnePage() {
        Page<String> page = Page.of(List.of(), 0, 3);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.hasNext()).isFalse();
    }
}
