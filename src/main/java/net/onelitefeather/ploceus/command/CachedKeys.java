package net.onelitefeather.ploceus.command;

import net.onelitefeather.ploceus.domain.PloceusKey;

import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/// A short-lived cache in front of the key list Cloud asks for while a player is typing.
///
/// {@link PloceusKeyParser#suggestionsFuture} is consulted once per keystroke, and it called
/// straight through to two unbounded selects — every `Project` and every `ProjectWorld`. On
/// the team build server's 76 worlds that is two full table scans per typed character, for a
/// list that changes a handful of times a day.
///
/// A time-to-live rather than explicit invalidation on create/delete: keys are created and
/// deleted from `ProjectService`, `WorldService`, `WorldCreationService`, `WorldImportService`
/// and both graphical channels' forms, so an invalidation hook would have to be threaded
/// through all of them — and quietly forgotten by whatever is added next. A TTL cannot be
/// forgotten, and its worst case is bounded: a key that was created in the last couple of
/// seconds is briefly missing from tab completion, while typing it out in full still works,
/// because this only ever backs *suggestions* and never parsing.
final class CachedKeys implements Supplier<List<PloceusKey>> {

    static final Duration TTL = Duration.ofSeconds(2);

    private final Supplier<List<PloceusKey>> source;
    private final LongSupplier nanoClock;
    private final long ttlNanos;

    private volatile List<PloceusKey> cached;
    private volatile long expiresAtNanos;

    CachedKeys(Supplier<List<PloceusKey>> source) {
        this(source, System::nanoTime, TTL);
    }

    CachedKeys(Supplier<List<PloceusKey>> source, LongSupplier nanoClock, Duration ttl) {
        this.source = source;
        this.nanoClock = nanoClock;
        this.ttlNanos = ttl.toNanos();
    }

    /// Two threads that miss at the same moment may both query. That is deliberate: locking
    /// would serialise tab completion for everyone on the server behind one database read,
    /// and the only cost of the race is a duplicated query that was about to happen anyway.
    @Override
    public List<PloceusKey> get() {
        long now = nanoClock.getAsLong();
        List<PloceusKey> snapshot = cached;
        if (snapshot != null && now - expiresAtNanos < 0) {
            return snapshot;
        }
        List<PloceusKey> fresh = source.get();
        cached = fresh;
        expiresAtNanos = now + ttlNanos;
        return fresh;
    }
}
