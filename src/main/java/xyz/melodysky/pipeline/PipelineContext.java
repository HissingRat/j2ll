package xyz.melodysky.pipeline;

import java.time.Clock;
import java.util.Objects;
import xyz.melodysky.diagnostic.DiagnosticBag;
import xyz.melodysky.dump.DumpSink;
import xyz.melodysky.dump.NoopDumpSink;

public final class PipelineContext {
    private final DiagnosticBag diagnostics;
    private final DumpSink dumpSink;
    private final Clock clock;

    public PipelineContext(DiagnosticBag diagnostics, DumpSink dumpSink, Clock clock) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.dumpSink = Objects.requireNonNull(dumpSink, "dumpSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static PipelineContext bootstrap() {
        return new PipelineContext(new DiagnosticBag(), NoopDumpSink.INSTANCE, Clock.systemUTC());
    }

    public static PipelineContext usingDiagnostics(DiagnosticBag diagnostics) {
        return new PipelineContext(diagnostics, NoopDumpSink.INSTANCE, Clock.systemUTC());
    }

    public DiagnosticBag diagnostics() {
        return diagnostics;
    }

    public DumpSink dumpSink() {
        return dumpSink;
    }

    public Clock clock() {
        return clock;
    }
}
