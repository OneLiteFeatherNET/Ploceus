package net.onelitefeather.ploceus.service;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import net.onelitefeather.ploceus.domain.Actor;
import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.platform.WorldSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the two properties that actually matter for instrumentation built on an API that
/// is a deliberate no-op without a registered SDK: it must never throw in that default state
/// (every server this plugin runs on, unless the OTel javaagent is attached), and it must
/// produce the documented span when something IS listening. The second half uses a real,
/// in-memory OpenTelemetry SDK rather than a mock - this exercises the actual `GlobalOpenTelemetry`
/// wiring `WorldImportService` depends on, not a recorded stub interaction.
class OpenTelemetryInstrumentationTest {

    private final Actor actor = new Actor(UUID.randomUUID(), "TheMeinerLP");

    /// `GlobalOpenTelemetry` is a JVM-wide singleton that can only be set once between resets,
    /// so every test starts and ends from the same clean, unset state regardless of what ran
    /// before or runs after it in the same JVM.
    @BeforeEach
    @AfterEach
    void resetGlobalOpenTelemetry() {
        GlobalOpenTelemetry.resetForTest();
    }

    private WorldSnapshot snapshot(String name) {
        return new WorldSnapshot(name, PloceusKey.fromWorldName(name), false, true, "", 0);
    }

    @Test
    void importInstrumentationDoesNotThrowWithoutARegisteredSdk() {
        // Deliberately no GlobalOpenTelemetry.set(...) call here: this is the default state
        // on every real server unless the OTel javaagent is attached, and the whole point of
        // building on the OTel *API* artifact rather than the SDK is that this must be safe.
        WorldFixtures fixtures = new WorldFixtures();

        var summary = fixtures.importService().importAll(List.of(snapshot("elytrarace-alien")), actor);

        assertThat(summary.imported()).isEqualTo(1);
    }

    @Test
    void importProducesTheDocumentedSpanWhenAnSdkIsRegistered() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());

        WorldFixtures fixtures = new WorldFixtures();
        fixtures.importService().importAll(List.of(snapshot("elytrarace-alien")), actor);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("ploceus.world.import");
        assertThat(span.getAttributes().get(AttributeKey.longKey("ploceus.import.candidate_count")))
                .isEqualTo(1L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("ploceus.import.imported_count")))
                .isEqualTo(1L);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }
}
