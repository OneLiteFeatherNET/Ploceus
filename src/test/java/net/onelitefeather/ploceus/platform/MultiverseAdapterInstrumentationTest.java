package net.onelitefeather.ploceus.platform;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A failed world load or unload must be visible in a trace as an error, not just a gap where
/// a span should have been - this is the regression test for that. `MultiverseAdapter` is
/// built with a `null` `MultiverseCoreApi` (the same technique `MultiverseAdapterThreadingTest`
/// uses: `MultiverseCoreApi` is `final` and cannot be faked otherwise), so every call below
/// fails with a `NullPointerException` once it reaches Multiverse - the span assertions confirm
/// that failure is recorded on the span, not swallowed by it, before it propagates to the
/// caller exactly as it did before this class had any tracing at all.
class MultiverseAdapterInstrumentationTest {

    private final InlineScheduler scheduler = new InlineScheduler();
    private final MultiverseAdapter adapter = new MultiverseAdapter(null, scheduler);

    @BeforeEach
    @AfterEach
    void resetGlobalOpenTelemetry() {
        GlobalOpenTelemetry.resetForTest();
    }

    @AfterEach
    void closeScheduler() {
        scheduler.close();
    }

    @Test
    void aFailedLoadRecordsTheExceptionAndMarksTheSpanAsAnError() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        installTracerProvider(exporter);

        assertThatThrownBy(() -> adapter.load("elytrarace-alien")).isInstanceOf(NullPointerException.class);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("ploceus.world.load");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("ploceus.world.name")))
                .isEqualTo("elytrarace-alien");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).as("the NullPointerException must be recorded on the span").isNotEmpty();
    }

    @Test
    void aFailedUnloadRecordsTheExceptionAndMarksTheSpanAsAnError() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        installTracerProvider(exporter);

        assertThatThrownBy(() -> adapter.unload("elytrarace-alien")).isInstanceOf(NullPointerException.class);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        SpanData span = spans.get(0);
        assertThat(span.getName()).isEqualTo("ploceus.world.unload");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).as("the NullPointerException must be recorded on the span").isNotEmpty();
    }

    private void installTracerProvider(InMemorySpanExporter exporter) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
    }

    /// A scheduler double whose "main thread" is whichever thread calls it - sufficient here
    /// since these tests only care about the span each call produces, not about thread-hopping
    /// (already covered by `MultiverseAdapterThreadingTest`).
    private static final class InlineScheduler extends SchedulerAdapter {
        InlineScheduler() {
            super(null);
        }

        @Override
        public boolean isMainThread() {
            return true;
        }
    }
}
