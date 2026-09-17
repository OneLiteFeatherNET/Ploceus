package net.onelitefeather.ploceus.platform;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;

/// Looks up the {@link Tracer}/{@link Meter} fresh from {@link GlobalOpenTelemetry} on every
/// call, rather than caching either in a `static final` field the way an SLF4J {@code Logger}
/// is cached.
///
/// This project's classes can be loaded, and their static initializers run, well before the
/// OpenTelemetry javaagent (or any other SDK) has necessarily installed itself as the global
/// instance - Guice builds most of the object graph eagerly in `onEnable`, and nothing
/// guarantees ordering between that and whatever sets the global `OpenTelemetry`.
/// {@code GlobalOpenTelemetry.getTracer(...)}/{@code .getMeter(...)} resolve
/// {@code GlobalOpenTelemetry.get()} at the moment they are called and bind the returned
/// `Tracer`/`Meter` to *that* instance permanently - a `static final Tracer TRACER =
/// GlobalOpenTelemetry.getTracer(...)` field bakes in whatever was global at class-load time
/// forever, even if a real SDK is installed moments later. (This is not theoretical: it is
/// exactly what made the first version of this instrumentation's own tests fail - a `Tracer`
/// cached at class-load time kept exporting to a no-op provider no matter what the test
/// installed afterwards.)
///
/// Every span/metric call site in this project goes through here instead, so it always
/// reflects whatever is globally registered right now. The lookup itself is documented by the
/// OpenTelemetry API as cheap enough to call per use; none of the operations this project
/// instruments (a world load/unload/create, the startup import, one reaper pass) are hot
/// enough for that to matter.
public final class Telemetry {

    /// The single instrumentation scope name every Ploceus span and metric is reported under.
    public static final String SCOPE = "net.onelitefeather.ploceus";

    private Telemetry() { }

    public static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(SCOPE);
    }

    public static Meter meter() {
        return GlobalOpenTelemetry.getMeter(SCOPE);
    }
}
