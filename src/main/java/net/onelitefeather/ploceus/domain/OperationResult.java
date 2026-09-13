package net.onelitefeather.ploceus.domain;

import java.util.function.Function;

/// The only result type services return. `messageKey` addresses a MiniMessage entry in
/// `Messages`; `detail` is for the log, never for the player.
public sealed interface OperationResult<T> {

    record Success<T>(T value) implements OperationResult<T> {}

    record Failure<T>(String messageKey, String detail) implements OperationResult<T> {}

    static <T> OperationResult<T> success(T value) {
        return new Success<>(value);
    }

    static <T> OperationResult<T> failure(String messageKey, String detail) {
        return new Failure<>(messageKey, detail);
    }

    default boolean isSuccess() {
        return this instanceof Success<T>;
    }

    default T orElse(T fallback) {
        return switch (this) {
            case Success<T> success -> success.value();
            case Failure<T> ignored -> fallback;
        };
    }

    default <R> OperationResult<R> map(Function<T, R> mapper) {
        return switch (this) {
            case Success<T> success -> success(mapper.apply(success.value()));
            case Failure<T> failure -> failure(failure.messageKey(), failure.detail());
        };
    }
}
