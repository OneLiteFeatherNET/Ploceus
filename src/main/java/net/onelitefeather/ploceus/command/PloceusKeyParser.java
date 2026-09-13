package net.onelitefeather.ploceus.command;

import net.onelitefeather.ploceus.domain.PloceusKey;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/// Parses a {@link PloceusKey} from either a bare value ("elytrarace") or a fully
/// qualified key ("ploceus:elytrarace"). Suggestions are repository-backed, supplied by
/// the caller so this class stays free of persistence imports (see
/// `ArchitectureTest.commandsMustNotReachPersistence`).
public class PloceusKeyParser implements ArgumentParser<CommandSender, PloceusKey>,
        SuggestionProvider<CommandSender> {

    private final Supplier<List<PloceusKey>> candidates;

    public PloceusKeyParser(Supplier<List<PloceusKey>> candidates) {
        this.candidates = candidates;
    }

    /// Pure, so it is testable without Cloud: bare values get the namespace prepended,
    /// fully qualified keys are accepted, everything else is rejected.
    public static Optional<PloceusKey> parseInput(String input) {
        try {
            if (input.indexOf(':') >= 0) {
                return Optional.of(PloceusKey.parse(input));
            }
            return Optional.of(PloceusKey.of(input));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public ArgumentParseResult<PloceusKey> parse(CommandContext<CommandSender> context, CommandInput input) {
        String raw = input.readString();
        return parseInput(raw)
                .map(ArgumentParseResult::success)
                .orElseGet(() -> ArgumentParseResult.failure(
                        new IllegalArgumentException("'" + raw + "' is not a valid Ploceus key")));
    }

    @Override
    public CompletableFuture<? extends Iterable<? extends Suggestion>> suggestionsFuture(
            CommandContext<CommandSender> context, CommandInput input) {
        return CompletableFuture.completedFuture(candidates.get().stream()
                .map(PloceusKey::value)
                .map(Suggestion::suggestion)
                .toList());
    }
}
