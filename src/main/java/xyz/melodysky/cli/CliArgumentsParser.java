package xyz.melodysky.cli;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;

public final class CliArgumentsParser {
    public static final Path DEFAULT_CONFIG_PATH = Path.of("Config.json");

    public CliParseResult parse(String[] args) {
        Objects.requireNonNull(args, "args");

        ArrayList<String> errors = new ArrayList<>();
        Path configPath = DEFAULT_CONFIG_PATH;
        boolean configSpecified = false;
        boolean validate = false;
        boolean dryRun = false;
        boolean debug = false;
        boolean help = false;
        boolean version = false;

        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (argument == null) {
                errors.add("Unknown argument: null");
                continue;
            }
            switch (argument) {
                case "--config" -> {
                    configSpecified = true;
                    if (index + 1 >= args.length || looksLikeOption(args[index + 1])) {
                        errors.add("Missing value for --config");
                        continue;
                    }
                    String value = args[++index];
                    try {
                        configPath = Path.of(value);
                    } catch (InvalidPathException exception) {
                        errors.add("Invalid path for --config: " + value);
                    }
                }
                case "--validate" -> validate = true;
                case "--dry-run" -> dryRun = true;
                case "--debug" -> debug = true;
                case "--help" -> help = true;
                case "--version" -> version = true;
                default -> errors.add("Unknown argument: " + argument);
            }
        }

        if (validate && dryRun) {
            errors.add("Options --validate and --dry-run are mutually exclusive");
        }
        boolean hasOperationalOption = configSpecified || validate || dryRun || debug;
        if (help && version) {
            errors.add("Options --help and --version are mutually exclusive");
        } else if (help && hasOperationalOption) {
            errors.add("Option --help must be used alone");
        } else if (version && hasOperationalOption) {
            errors.add("Option --version must be used alone");
        }

        if (!errors.isEmpty()) {
            return CliParseResult.failure(errors);
        }

        CliMode mode = validate
                ? CliMode.VALIDATE
                : dryRun ? CliMode.DRY_RUN : CliMode.BUILD;
        return CliParseResult.success(new CliOptions(mode, configPath, debug, help, version));
    }

    private boolean looksLikeOption(String argument) {
        return argument == null || argument.startsWith("--");
    }
}
