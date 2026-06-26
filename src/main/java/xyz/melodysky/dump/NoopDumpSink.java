package xyz.melodysky.dump;

public enum NoopDumpSink implements DumpSink {
    INSTANCE;

    @Override
    public void write(DumpKind kind, String artifactId, String content) {
        // Intentionally empty. Dump output is opt-in through a concrete sink.
    }
}
