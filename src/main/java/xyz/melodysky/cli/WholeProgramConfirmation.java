package xyz.melodysky.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.analysis.world.WholeProgramAnalysisFeature;
import xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy;
import xyz.melodysky.analysis.world.WholeProgramAnalysisRequirement;
import xyz.melodysky.analysis.world.WholeProgramAnalysisRequirements;
import xyz.melodysky.config.ResolvedConfig;

final class WholeProgramConfirmation {
    Result confirm(
            ResolvedConfig config,
            InputStream input,
            PrintStream err) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(err, "err");
        return confirm(
                config,
                new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)),
                err);
    }

    Result confirm(
            ResolvedConfig config,
            BufferedReader reader,
            PrintStream err) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(err, "err");
        List<WholeProgramAnalysisRequirement> requirements =
                new WholeProgramAnalysisRequirements().forConfig(config);
        if (requirements.isEmpty()) {
            return Result.accepted(WholeProgramAnalysisPolicy.strict());
        }

        EnumSet<WholeProgramAnalysisFeature> approvals =
                EnumSet.noneOf(WholeProgramAnalysisFeature.class);
        for (WholeProgramAnalysisRequirement requirement : requirements) {
            err.println("warning=" + requirement.warning());
            while (true) {
                err.println(requirement.prompt());
                err.print("> ");
                err.flush();
                String answer = reader.readLine();
                if (answer == null) {
                    err.println();
                    err.println("cancelled=" + requirement.feature().displayName()
                            + " confirmation was not provided");
                    return Result.rejected();
                }
                String normalized = answer.trim();
                if (normalized.equalsIgnoreCase("Y")) {
                    approvals.add(requirement.feature());
                    err.println("analysisScope=" + requirement.feature().displayName()
                            + ":currentJarOnlyUserApproved");
                    break;
                }
                if (normalized.equalsIgnoreCase("N")) {
                    err.println("cancelled=" + requirement.feature().displayName()
                            + " requires CLOSED_WORLD");
                    return Result.rejected();
                }
                err.println("Please answer Y or N.");
            }
        }
        return Result.accepted(WholeProgramAnalysisPolicy.currentJarOnly(approvals));
    }

    record Result(boolean accepted, WholeProgramAnalysisPolicy policy) {
        Result {
            Objects.requireNonNull(policy, "policy");
        }

        static Result accepted(WholeProgramAnalysisPolicy policy) {
            return new Result(true, policy);
        }

        static Result rejected() {
            return new Result(false, WholeProgramAnalysisPolicy.strict());
        }
    }
}
