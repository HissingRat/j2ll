package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeRegistrationOptimizedAssemblyStackBaseMutationTest {
    @TempDir
    Path temp;

    @Test
    void rejectsX64StackAndFrameBaseMutationBeforeReadback() throws Exception {
        NativeRegistrationControlTopologyPlan plan = plan("x64-base-mutation");
        TargetTriple target = TargetTriple.LINUX_X64;
        String valid = assembly(target, plan);
        for (String mutation : List.of(
                "addq\t$16, %rsp",
                "subq\t$16, %rsp",
                "leaq\t16(%rsp), %rsp",
                "movq\t%rax, %rsp",
                "andq\t$-16, %rsp",
                "incq\t%rsp",
                "decq\t%rsp",
                "xchgq\t%rsp, %rax",
                "pushq\t%rax",
                "popq\t%rax",
                "enter\t$16, $0",
                "leave")) {
            assertRejected(
                    target,
                    plan,
                    between(
                            valid,
                            "\tmovl\t%eax, -4(%rsp)",
                            "\tmovl\t-4(%rsp), %eax",
                            "\t" + mutation));
        }
        assertRejected(
                target,
                plan,
                replaceOnce(
                        valid,
                        "\tmovl\t%eax, -4(%rsp)\n\tmovl\t-4(%rsp), %eax",
                        "\tmovl\t%eax, -4(%rbp)\n"
                                + "\tmovq\t%rsp, %rbp\n"
                                + "\tmovl\t-4(%rbp), %eax"));
    }

    @Test
    void rejectsA64StackAndFrameBaseMutationBeforeReadback() throws Exception {
        NativeRegistrationControlTopologyPlan plan = plan("a64-base-mutation");
        TargetTriple target = TargetTriple.LINUX_ARM64;
        String valid = assembly(target, plan);
        for (String mutation : List.of(
                "add\tsp, sp, #16",
                "sub\tsp, sp, #16",
                "mov\tsp, x9",
                "str\tx9, [sp, #-16]!",
                "ldr\tx9, [sp], #16",
                "stp\tx9, x10, [sp, #-16]!",
                "ldp\tx9, x10, [sp], #16")) {
            assertRejected(
                    target,
                    plan,
                    between(
                            valid,
                            "\tstr\tw0, [sp, #4]",
                            "\tldr\tw0, [sp, #4]",
                            "\t" + mutation));
        }
        assertRejected(
                target,
                plan,
                replaceOnce(
                        valid,
                        "\tstr\tw0, [sp, #4]\n\tldr\tw0, [sp, #4]",
                        "\tstr\tw0, [x29, #4]\n"
                                + "\tmov\tx29, x9\n"
                                + "\tldr\tw0, [x29, #4]"));
    }

    private void assertRejected(
            TargetTriple target,
            NativeRegistrationControlTopologyPlan plan,
            String assembly) throws Exception {
        Path evidence = temp.resolve(target.directoryName() + "-" + System.nanoTime() + ".s");
        Files.writeString(evidence, assembly);
        IOException failure = assertThrows(
                IOException.class,
                () -> new NativeRegistrationOptimizedAssemblyGate()
                        .verifyTarget(target, List.of(evidence), plan));
        assertTrue(
                failure.getMessage().contains("POST_CALL_STACK_BASE_MUTATION"),
                failure.getMessage());
    }

    private String between(String source, String before, String after, String inserted) {
        return replaceOnce(source, before + "\n" + after, before + "\n" + inserted + "\n" + after);
    }

    private String replaceOnce(String source, String expected, String replacement) {
        int index = source.indexOf(expected);
        if (index < 0) {
            throw new IllegalArgumentException("missing fixture text: " + expected);
        }
        return source.substring(0, index) + replacement + source.substring(index + expected.length());
    }

    private NativeRegistrationControlTopologyPlan plan(String identity) {
        return NativeRegistrationControlTestFixture.emission(11, identity).topologyPlan();
    }

    private String assembly(
            TargetTriple target,
            NativeRegistrationControlTopologyPlan plan) {
        return new NativeRegistrationOptimizedAssemblyFixture().assembly(target, plan);
    }
}
