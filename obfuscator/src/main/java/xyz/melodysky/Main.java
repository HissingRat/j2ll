package xyz.melodysky;

import sun.misc.Signal;
import xyz.melodysky.app.J2llApplication;
import xyz.melodysky.app.OutputPathFormatter;
import xyz.melodysky.config.Config;
import xyz.melodysky.process.SubprocessRegistry;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;

public class Main {

    static void main(String[] args) {
        System.setProperty("java.net.useSystemProxies", "true");
        installCancellationSignalHandlers(Thread.currentThread());
        int exitCode;
        try {
            CliOptions options = parseArgs(args);
            Config config = options.configPath() == null
                    ? Config.loadOrCreateDefault()
                    : Config.load(options.configPath());
            if (config == null) {
                exitCode = 0;
            } else if (options.analyze()) {
                exitCode = new J2llApplication(Main.class).analyze(config);
            } else {
                exitCode = new J2llApplication(Main.class).run(config, options.debug());
            }
        } catch (Exception exception) {
            if (isCancellation(exception)) {
                restoreInterruptStatusIfNeeded(exception);
                System.out.println();
                System.out.println("Build cancelled by user.");
                exitCode = 130;
            } else {
                throw new RuntimeException("Failed to run j2ll", exception);
            }
        }
        System.exit(exitCode);
    }

    static void installCancellationSignalHandlers(Thread mainThread) {
        installCancellationSignalHandler("INT", mainThread);
        installCancellationSignalHandler("TERM", mainThread);
    }

    private static void installCancellationSignalHandler(String signalName, Thread mainThread) {
        try {
            Signal.handle(new Signal(signalName), ignored -> {
                SubprocessRegistry.requestShutdownNow();
                if (mainThread != null) {
                    mainThread.interrupt();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    static boolean isCancellation(Throwable throwable) {
        if (SubprocessRegistry.isShutdownRequested()) {
            return true;
        }
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    private static void restoreInterruptStatusIfNeeded(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static CliOptions parseArgs(String[] args) {
        boolean debug = false;
        boolean analyze = false;
        Path configPath = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--debug" -> debug = true;
                case "--analyze" -> analyze = true;
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value for --config");
                    }
                    configPath = Path.of(args[++i]);
                }
                default -> throw new IllegalArgumentException(
                        "Usage: java -jar j2ll.jar [--debug] [--analyze] [--config <file>]"
                );
            }
        }
        return new CliOptions(debug, analyze, configPath);
    }

    static String formatIrOutputHint(Path artifactPath) {
        return OutputPathFormatter.formatIrOutputHint(artifactPath);
    }

    private record CliOptions(boolean debug, boolean analyze, Path configPath) {
    }
}
