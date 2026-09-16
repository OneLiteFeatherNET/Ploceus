package net.onelitefeather.ploceus.domain;

import java.util.function.Function;

/// The only result type services return.
///
/// `messageKey` addresses a MiniMessage entry in `Messages`.
///
/// `detail` **is player-facing**, and always was: eleven of those templates render it
/// (`project.unknown`, `world.exists`, `world.create.failed` and the rest all say
/// `<detail>`), and all three channels pass it straight into MiniMessage. This doc used to
/// claim the opposite, which made it easy to put something there that no player should see.
///
/// What it must be is therefore a rule, not a preference: **a short, safe operand** — the
/// key that was not found, the world name that already exists, the value that failed to
/// parse. Never exception text, never a stack trace, never a SQL fragment, never a file
/// path. Anything with diagnostic value beyond that belongs in the log, next to the
/// exception it came from; the player gets the name of the thing they typed.
public sealed interface OperationResult<T> {

    record Success<T>(T value) implements OperationResult<T> {}

    /// `detail` is rendered to the player as `<detail>`; see the interface doc for what may
    /// go in it.
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
