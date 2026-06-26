package xyz.melodysky.dump;

public interface DumpSink {
    void write(DumpKind kind, String artifactId, String content);
}
