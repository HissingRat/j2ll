package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZigBuildInvokerTest {
    @TempDir
    Path temp;

    @Test
    void invocationUsesManagedZigBuildWithoutHostCcFallback() {
        ManagedZig zig = new ManagedZig(
                temp.resolve("j2ll-home/zig/zig"),
                temp.resolve("j2ll-home/zig"),
                "0.15.2",
                "checksumSignatureInterfacePresent:notYetHardcoded");

        ZigBuildInvocation invocation = new ZigBuildInvoker().invocation(zig, ZigBuildWorkspace.under(temp));

        assertEquals(zig.executable().toString(), invocation.command().get(0));
        assertEquals("build", invocation.command().get(1));
        String commandTail = String.join(" ", invocation.command().subList(1, invocation.command().size()))
                .toLowerCase(Locale.ROOT);
        assertFalse(commandTail.contains("zig cc"));
        assertFalse(commandTail.contains("clang"));
        assertFalse(commandTail.contains("gcc"));
        assertFalse(commandTail.matches(".*(^|\\s)cc(\\s|$).*"));
        assertFalse(commandTail.matches(".*(^|\\s)ld(\\s|$).*"));
    }
}
