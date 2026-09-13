package net.onelitefeather.ploceus.ui;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class UiRouterTest {

    private static final int MIN_DIALOG_PROTOCOL = 771;

    @Test
    void aModernClientGetsTheDialogChannel() {
        assertThat(UiRouter.choose(772, Optional.empty(), MIN_DIALOG_PROTOCOL))
                .isEqualTo(UiChannel.DIALOG);
    }

    @Test
    void aClientExactlyAtTheThresholdGetsTheDialogChannel() {
        assertThat(UiRouter.choose(771, Optional.empty(), MIN_DIALOG_PROTOCOL))
                .isEqualTo(UiChannel.DIALOG);
    }

    @Test
    void anOlderClientFallsBackToTheInventory() {
        assertThat(UiRouter.choose(770, Optional.empty(), MIN_DIALOG_PROTOCOL))
                .isEqualTo(UiChannel.INVENTORY);
    }

    @Test
    void anExplicitPreferenceBeatsTheProtocolVersion() {
        assertThat(UiRouter.choose(772, Optional.of(UiChannel.CHAT), MIN_DIALOG_PROTOCOL))
                .isEqualTo(UiChannel.CHAT);
        assertThat(UiRouter.choose(700, Optional.of(UiChannel.DIALOG), MIN_DIALOG_PROTOCOL))
                .isEqualTo(UiChannel.DIALOG);
    }
}
