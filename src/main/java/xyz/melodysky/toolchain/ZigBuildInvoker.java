package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ZigBuildInvoker {
    private final ZigCommandRunner runner;

    public ZigBuildInvoker() {
        this(ZigCommandRunner.process());
    }

    public ZigBuildInvoker(ZigCommandRunner runner) {
        this.runner = runner;
    }

    public ZigBuildInvocation invocation(ManagedZig zig, ZigBuildWorkspace workspace) {
        ArrayList<String> command = new ArrayList<>();
        command.add(zig.executable().toString());
        command.add("build");
        command.add("--prefix");
        command.add(workspace.workspaceRoot().toAbsolutePath().normalize().toString());
        command.add("--cache-dir");
        command.add(workspace.workspaceRoot().resolve("native/zig-cache/local").toAbsolutePath().normalize().toString());
        command.add("--global-cache-dir");
        command.add(workspace.workspaceRoot().resolve("native/zig-cache/global").toAbsolutePath().normalize().toString());
        return new ZigBuildInvocation(
                zig.executable(),
                workspace.buildDirectory(),
                List.copyOf(command),
                workspace.logsDirectory().resolve("zig-build.log"));
    }

    public void invoke(ManagedZig zig, ZigBuildWorkspace workspace) throws IOException {
        ZigBuildInvocation invocation = invocation(zig, workspace);
        Files.createDirectories(invocation.logFile().getParent());
        ZigCommandResult result = runner.run(
                invocation.command(),
                invocation.workingDirectory(),
                ZigWorkspaceEnvironment.environment(workspace.workspaceRoot()));
        Files.writeString(
                invocation.logFile(),
                "$ " + String.join(" ", invocation.command()) + System.lineSeparator()
                        + result.combinedOutput(),
                StandardCharsets.UTF_8);
        if (result.exitCode() != 0) {
            String output = result.combinedOutput();
            String tail = output.length() > 4000 ? output.substring(output.length() - 4000) : output;
            throw new IOException("managed Zig build failed with exit code " + result.exitCode()
                    + "; see " + invocation.logFile().toAbsolutePath()
                    + System.lineSeparator()
                    + tail);
        }
    }
}
