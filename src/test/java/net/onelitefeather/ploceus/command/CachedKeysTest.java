package net.onelitefeather.ploceus.command;

import net.onelitefeather.ploceus.domain.PloceusKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// Cloud asks `PloceusKeyParser.suggestionsFuture` for the key list on every keystroke, and
/// it used to run two unbounded selects each time — on 76 worlds, two full table scans per
/// typed character.
class CachedKeysTest {

    private final AtomicInteger queries = new AtomicInteger();
    private final AtomicLong clock = new AtomicLong();

    private CachedKeys<PloceusKey> cache(Duration ttl) {
        return new CachedKeys<>(() -> {
            queries.incrementAndGet();
            return List.of(PloceusKey.of("elytrarace"));
        }, clock::get, ttl);
    }

    @Test
    void aBurstOfKeystrokesCostsOneQuery() {
        CachedKeys<PloceusKey> keys = cache(Duration.ofSeconds(2));

        for (int keystroke = 0; keystroke < 12; keystroke++) {
            clock.addAndGet(Duration.ofMillis(80).toNanos());
            assertThat(keys.get()).containsExactly(PloceusKey.of("elytrarace"));
        }

        assertThat(queries.get()).isEqualTo(1);
    }

    @Test
    void theListIsRereadOnceTheTimeToLiveHasPassed() {
        CachedKeys<PloceusKey> keys = cache(Duration.ofSeconds(2));

        keys.get();
        clock.addAndGet(Duration.ofSeconds(3).toNanos());
        keys.get();

        assertThat(queries.get()).isEqualTo(2);
    }

    /// `System.nanoTime` may start anywhere, including deep in negative territory, so the
    /// expiry comparison has to be overflow-safe rather than a plain `now < expiresAt`.
    @Test
    void theCacheStillWorksWhenTheClockIsNearOverflow() {
        clock.set(Long.MAX_VALUE - Duration.ofMillis(500).toNanos());
        CachedKeys<PloceusKey> keys = cache(Duration.ofSeconds(2));

        keys.get();
        clock.addAndGet(Duration.ofMillis(100).toNanos());
        keys.get();

        assertThat(queries.get()).isEqualTo(1);
    }

    @Test
    void theDefaultTimeToLiveIsShortEnoughToGoUnnoticed() {
        assertThat(CachedKeys.TTL).isLessThanOrEqualTo(Duration.ofSeconds(5));
    }
}
