package net.onelitefeather.ploceus.platform;

import net.onelitefeather.ploceus.domain.OperationResult;
import org.junit.jupiter.api.Test;
import org.mvplugins.multiverse.core.utils.result.Attempt;
import org.mvplugins.multiverse.core.world.reasons.ImportFailureReason;
import org.mvplugins.multiverse.external.vavr.control.Try;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// Catches the defect this project shipped to a real server: `MultiverseAdapter.setAutoLoad`
/// reported success after Multiverse accepted the change in memory, without ever calling
/// `WorldManager.saveWorldsConfig()` — so `worlds.yml` never changed, and every world loaded
/// again on the next boot despite the log saying otherwise.
///
/// ## Why this is not a `WorldManager`/`MultiverseCoreApi` test
///
/// Both are `final` (see `javap` on `libs/multiverse-core-5.8.1.jar`), this project has no
/// Mockito dependency, and `MultiverseAdapterThreadingTest` already establishes the pattern
/// this suite follows for that reason: reach into the adapter with the smallest real thing
/// that can expose the bug instead of a hand-rolled subclass that would just override the
/// method under test.
///
/// `MultiverseAdapter.foldMutationThenSave` is that smallest thing. It is the exact function
/// `setAutoLoadOnMainThread`/`setAliasOnMainThread` delegate to, it takes a Vavr `Try<Void>`
/// and a `Supplier<Try<Void>>` — both plain, publicly constructible types from the shaded
/// Vavr in that same jar, no Bukkit/Multiverse server required — and it decides exactly the
/// thing that was wrong: whether a successful mutation alone is enough to report success. A
/// regression that stops calling the save supplier, or that reports success when the save
/// fails, fails these assertions without needing a running server.
class MultiverseAdapterPersistenceTest {

    @Test
    void aSuccessfulMutationThatSavesIsAnOverallSuccess() {
        AtomicInteger saveCalls = new AtomicInteger();
        OperationResult<Void> result = MultiverseAdapter.foldMutationThenSave(
                Try.success(null), "elytrarace-alien", "world.autoload.failed",
                () -> {
                    saveCalls.incrementAndGet();
                    return Try.success(null);
                });

        assertThat(result.isSuccess()).isTrue();
        assertThat(saveCalls.get())
                .as("a successful mutation must be followed by exactly one persist attempt")
                .isEqualTo(1);
    }

    @Test
    void aSuccessfulMutationThatFailsToSaveIsReportedAsFailure() {
        OperationResult<Void> result = MultiverseAdapter.foldMutationThenSave(
                Try.success(null), "elytrarace-alien", "world.autoload.failed",
                () -> Try.failure(new java.io.IOException("disk full")));

        assertThat(result.isSuccess()).isFalse();
        var failure = (OperationResult.Failure<Void>) result;
        assertThat(failure.messageKey()).isEqualTo("world.autoload.failed");
        assertThat(failure.detail())
                .as("the player/operator gets the world name, never the exception text")
                .isEqualTo("elytrarace-alien");
    }

    @Test
    void aFailedMutationNeverAttemptsToSave() {
        AtomicInteger saveCalls = new AtomicInteger();
        OperationResult<Void> result = MultiverseAdapter.foldMutationThenSave(
                Try.failure(new RuntimeException("Multiverse refused it")), "elytrarace-alien",
                "world.alias.failed", () -> {
                    saveCalls.incrementAndGet();
                    return Try.success(null);
                });

        assertThat(result.isSuccess()).isFalse();
        var failure = (OperationResult.Failure<Void>) result;
        assertThat(failure.messageKey()).isEqualTo("world.alias.failed");
        assertThat(saveCalls.get())
                .as("a mutation that never took effect must not be flushed to worlds.yml")
                .isZero();
    }

    // --- foldAttemptThenSave: the same durability contract, for MultiverseAdapter.importWorld ---
    //
    // `WorldManager.importWorld` hands back an `Attempt<LoadedMultiverseWorld,
    // ImportFailureReason>` rather than the `Try<Void>` a property setter returns, so it
    // folds through a separate function - but it must decide the exact same thing
    // `foldMutationThenSave` decides for `setAutoLoad`/`setAlias`: a successful Multiverse
    // call alone is not enough, only a successful call *and* a successful
    // `saveWorldsConfig()` together count as done. `Attempt.success`/`Attempt.failure` are
    // real, publicly constructible values (unlike `WorldManager` itself), so this needs no
    // live Multiverse plugin either.

    @Test
    void aSuccessfulImportThatSavesIsAnOverallSuccess() {
        AtomicInteger saveCalls = new AtomicInteger();
        Attempt<Object, ImportFailureReason> attempt = Attempt.success(new Object());

        OperationResult<String> result = MultiverseAdapter.foldAttemptThenSave(
                attempt, "elytrarace-alien", "world.import.failed",
                () -> {
                    saveCalls.incrementAndGet();
                    return Try.success(null);
                });

        assertThat(result.isSuccess()).isTrue();
        assertThat(((OperationResult.Success<String>) result).value()).isEqualTo("elytrarace-alien");
        assertThat(saveCalls.get())
                .as("a successful import must be followed by exactly one persist attempt")
                .isEqualTo(1);
    }

    @Test
    void aSuccessfulImportThatFailsToSaveIsReportedAsFailure() {
        Attempt<Object, ImportFailureReason> attempt = Attempt.success(new Object());

        OperationResult<String> result = MultiverseAdapter.foldAttemptThenSave(
                attempt, "elytrarace-alien", "world.import.failed",
                () -> Try.failure(new java.io.IOException("disk full")));

        assertThat(result.isSuccess()).isFalse();
        var failure = (OperationResult.Failure<String>) result;
        assertThat(failure.messageKey()).isEqualTo("world.import.failed");
        assertThat(failure.detail())
                .as("the player/operator gets the world name, never the exception text")
                .isEqualTo("elytrarace-alien");
    }

    @Test
    void aFailedImportNeverAttemptsToSave() {
        AtomicInteger saveCalls = new AtomicInteger();
        Attempt<Object, ImportFailureReason> attempt = Attempt.failure(ImportFailureReason.WORLD_EXIST_LOADED);

        OperationResult<String> result = MultiverseAdapter.foldAttemptThenSave(
                attempt, "elytrarace-alien", "world.import.failed",
                () -> {
                    saveCalls.incrementAndGet();
                    return Try.success(null);
                });

        assertThat(result.isSuccess()).isFalse();
        var failure = (OperationResult.Failure<String>) result;
        assertThat(failure.detail()).isEqualTo("WORLD_EXIST_LOADED");
        assertThat(saveCalls.get())
                .as("an import that never took effect must not be flushed to worlds.yml")
                .isZero();
    }
}
