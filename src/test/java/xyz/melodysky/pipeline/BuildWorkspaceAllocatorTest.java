package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildWorkspaceAllocatorTest {
    @TempDir
    Path temp;

    @Test
    void createsTimestampedWorkspaceUnderOutputDirectory() throws Exception {
        Path outputDirectory = temp.resolve("missing/out");
        BuildWorkspaceAllocator allocator = new BuildWorkspaceAllocator(fixedClock());

        Path workspace = allocator.create(outputDirectory);

        assertEquals(outputDirectory.resolve("build_2026-07-22_01-02-03"), workspace);
        assertTrue(Files.isDirectory(workspace));
    }

    @Test
    void addsIncreasingSuffixWhenTimestampNameAlreadyExists() throws Exception {
        Path outputDirectory = temp.resolve("out");
        BuildWorkspaceAllocator allocator = new BuildWorkspaceAllocator(fixedClock());

        Path first = allocator.create(outputDirectory);
        Path second = allocator.create(outputDirectory);
        Path third = allocator.create(outputDirectory);

        assertEquals(outputDirectory.resolve("build_2026-07-22_01-02-03"), first);
        assertEquals(outputDirectory.resolve("build_2026-07-22_01-02-03-1"), second);
        assertEquals(outputDirectory.resolve("build_2026-07-22_01-02-03-2"), third);
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-22T01:02:03Z"), ZoneOffset.UTC);
    }
}
