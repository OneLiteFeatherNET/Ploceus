package net.onelitefeather.ploceus.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OperationResultTest {

    @Test
    void success_carriesItsValue() {
        OperationResult<String> result = OperationResult.success("world");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElse("fallback")).isEqualTo("world");
    }

    @Test
    void failure_fallsBackAndKeepsTheMessage() {
        OperationResult<String> result = OperationResult.failure("world.load.failed", "disk full");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.orElse("fallback")).isEqualTo("fallback");
        assertThat(result).isInstanceOf(OperationResult.Failure.class);
        assertThat(((OperationResult.Failure<String>) result).detail()).isEqualTo("disk full");
    }

    @Test
    void map_transformsSuccessAndPassesFailureThrough() {
        assertThat(OperationResult.success(2).map(i -> i * 3).orElse(0)).isEqualTo(6);
        assertThat(OperationResult.<Integer>failure("x", "y").map(i -> i * 3).orElse(0)).isEqualTo(0);
    }

    @Test
    void patternMatchingCoversBothCases() {
        String rendered = switch (OperationResult.success(7)) {
            case OperationResult.Success<Integer> s -> "ok:" + s.value();
            case OperationResult.Failure<Integer> f -> "err:" + f.messageKey();
        };
        assertThat(rendered).isEqualTo("ok:7");
    }
}
