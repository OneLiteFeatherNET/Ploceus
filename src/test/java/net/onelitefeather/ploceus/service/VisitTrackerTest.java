package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class VisitTrackerTest {

    @Test
    void collapsesRepeatedVisitsToTheLatestTimestamp() {
        var recorded = new ArrayList<Map<PloceusKey, Instant>>();
        VisitTracker tracker = trackerWriting(recorded, Map.of("elytrarace-desert",
                PloceusKey.of("elytrarace-desert")));

        Instant early = Instant.parse("2026-09-13T10:00:00Z");
        Instant late = Instant.parse("2026-09-13T10:05:00Z");
        tracker.recordVisit("elytrarace-desert", early);
        tracker.recordVisit("elytrarace-desert", late);

        assertThat(tracker.pendingCount()).isEqualTo(1);
        tracker.flush();

        assertThat(recorded).hasSize(1);
        assertThat(recorded.getFirst()).containsEntry(PloceusKey.of("elytrarace-desert"), late);
    }

    @Test
    void flushWithNothingPendingWritesNothing() {
        var recorded = new ArrayList<Map<PloceusKey, Instant>>();
        trackerWriting(recorded, Map.of()).flush();
        assertThat(recorded).isEmpty();
    }

    @Test
    void flushingAnUnknownWorldWritesNothing() {
        var recorded = new ArrayList<Map<PloceusKey, Instant>>();
        VisitTracker tracker = trackerWriting(recorded, Map.of());
        tracker.recordVisit("not-in-ploceus", Instant.now());

        // Buffered by name on the hot path — the repository is never consulted here.
        assertThat(tracker.pendingCount()).isEqualTo(1);

        tracker.flush();

        assertThat(recorded).isEmpty();
        assertThat(tracker.pendingCount()).isZero();
    }

    @Test
    void flushClearsThePendingBuffer() {
        var recorded = new ArrayList<Map<PloceusKey, Instant>>();
        VisitTracker tracker = trackerWriting(recorded, Map.of("a", PloceusKey.of("a")));
        tracker.recordVisit("a", Instant.now());
        tracker.flush();
        assertThat(tracker.pendingCount()).isZero();
    }

    private VisitTracker trackerWriting(List<Map<PloceusKey, Instant>> sink,
                                        Map<String, PloceusKey> known) {
        var repository = new net.onelitefeather.ploceus.persistence.WorldRepository(null) {
            @Override public Optional<ProjectWorld> findByWorldName(String name) {
                PloceusKey key = known.get(name);
                return key == null ? Optional.empty() : Optional.of(new ProjectWorld(key, name));
            }
            @Override public void updateLastVisited(Map<PloceusKey, Instant> visits) {
                if (!visits.isEmpty()) {
                    sink.add(Map.copyOf(visits));
                }
            }
        };
        return new VisitTracker(repository, null);
    }
}
