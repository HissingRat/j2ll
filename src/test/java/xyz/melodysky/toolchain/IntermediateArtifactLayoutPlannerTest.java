package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.config.IntermediatesConfig;
import xyz.melodysky.pipeline.LoweringStatus;

class IntermediateArtifactLayoutPlannerTest {
    @TempDir
    Path temp;

    @Test
    void plansOverloadedMethodIdsFromDescriptorHash() {
        IntermediateArtifactLayout layout = new IntermediateArtifactLayoutPlanner().plan(List.of(
                new ClassArtifactInput(
                        "pkg/Foo",
                        "pkg/Foo.class",
                        List.of(
                                method("pkg/Foo", "doWork", "(I)V", LoweringStatus.NATIVE_LOWERED),
                                method("pkg/Foo", "doWork", "(J)V", LoweringStatus.SKIPPED)))));

        List<MethodArtifact> methods = layout.methodsFor("pkg/Foo");

        assertEquals(2, methods.size());
        assertTrue(methods.get(0).methodId().startsWith("doWork__"));
        assertNotEquals(methods.get(0).methodId(), methods.get(1).methodId());
    }

    @Test
    void expandsClassHashPrefixWhenDirectoriesCollide() {
        IntermediateArtifactLayout layout = new IntermediateArtifactLayoutPlanner(this::collisionHash).plan(List.of(
                new ClassArtifactInput("pkg/类", "pkg/类.class", List.of()),
                new ClassArtifactInput("pkg/_u7c7b_", "pkg/_u7c7b_.class", List.of())));

        assertEquals(2, layout.classes().size());
        assertEquals(24, layout.classes().get(0).hashPrefixLength());
        assertEquals(24, layout.classes().get(1).hashPrefixLength());
        assertNotEquals(layout.classes().get(0).directory(), layout.classes().get(1).directory());
    }

    @Test
    void expandsMethodHashPrefixWhenIdsCollideInsideClass() {
        IntermediateArtifactLayout layout = new IntermediateArtifactLayoutPlanner(this::collisionHash).plan(List.of(
                new ClassArtifactInput(
                        "pkg/Foo",
                        "pkg/Foo.class",
                        List.of(
                                method("pkg/Foo", "same", "(I)V", LoweringStatus.NATIVE_LOWERED),
                                method("pkg/Foo", "same", "(J)V", LoweringStatus.NATIVE_LOWERED)))));

        List<MethodArtifact> methods = layout.methodsFor("pkg/Foo");

        assertEquals(24, methods.get(0).hashPrefixLength());
        assertEquals(24, methods.get(1).hashPrefixLength());
        assertNotEquals(methods.get(0).methodId(), methods.get(1).methodId());
    }

    @Test
    void writesClassAndMethodIndexes() {
        IntermediateArtifactLayout layout = new IntermediateArtifactLayoutPlanner().plan(List.of(
                new ClassArtifactInput(
                        "pkg/Foo$Bar",
                        "pkg/Foo$Bar.class",
                        List.of(method("pkg/Foo$Bar", "<init>", "()V", LoweringStatus.SKIPPED)))));
        ClassArtifact owner = layout.classes().get(0);
        IntermediateArtifactIndexWriter writer = new IntermediateArtifactIndexWriter();

        String classIndex = writer.classIndexJson(owner);
        String methodIndex = writer.methodIndexJson(owner, layout);

        assertTrue(classIndex.contains("\"internalName\": \"pkg/Foo$Bar\""));
        assertTrue(classIndex.contains("\"directory\": \"pkg/Foo$Bar__"));
        assertTrue(methodIndex.contains("\"methodId\": \"_init___"));
        assertTrue(methodIndex.contains("\"status\": \"skipped\""));
    }

    @Test
    void writesIntermediateManifestWithConfigFlagsAndFileHashes() throws Exception {
        IntermediateArtifactLayout layout = new IntermediateArtifactLayoutPlanner().plan(List.of(
                new ClassArtifactInput(
                        "pkg/Foo",
                        "pkg/Foo.class",
                        List.of(method("pkg/Foo", "add", "(II)I", LoweringStatus.NATIVE_LOWERED)))));
        Path file = temp.resolve("intermediates/classes/pkg_Foo__abc/llvm/class.ll");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "define hidden i32 @f() { ret i32 1 }\n");

        String manifest = new IntermediateArtifactIndexWriter().manifestJson(
                temp,
                new IntermediatesConfig(true, false, true, true, false),
                layout);

        assertTrue(manifest.contains("\"reportVersion\": 1"));
        assertTrue(manifest.contains("\"includeDebugDumps\": false"));
        assertTrue(manifest.contains("\"class\": \"pkg/Foo\""));
        assertTrue(manifest.contains("\"methodId\": \"add__"));
        assertTrue(manifest.contains("\"path\": \"intermediates/classes/pkg_Foo__abc/llvm/class.ll\""));
        assertTrue(manifest.contains("\"kind\": \"llvm\""));
        assertTrue(manifest.contains("\"sha256\""));
    }

    private MethodArtifactInput method(String owner, String name, String descriptor, LoweringStatus status) {
        return new MethodArtifactInput(owner, name, descriptor, status);
    }

    private String collisionHash(String input) {
        if (input.contains("类") || input.contains("(I)V")) {
            return "aaaaaaaaaaaaaaaa11111111bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        }
        if (input.contains("_u7c7b_") || input.contains("(J)V")) {
            return "aaaaaaaaaaaaaaaa22222222bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        }
        return "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    }
}
