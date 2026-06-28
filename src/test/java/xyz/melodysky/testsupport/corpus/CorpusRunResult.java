package xyz.melodysky.testsupport.corpus;

import java.nio.file.Path;
import xyz.melodysky.pipeline.MainlinePipelineResult;
import xyz.melodysky.testsupport.JvmRunResult;

public record CorpusRunResult(
        CorpusCase corpusCase,
        Path inputJar,
        MainlinePipelineResult pipelineResult,
        JvmRunResult originalRun,
        JvmRunResult outputRun,
        CorpusReportPaths reportPaths) {
    public boolean normalizedOutputMatches() {
        return originalRun != null
                && outputRun != null
                && originalRun.exitCode() == outputRun.exitCode()
                && normalize(originalRun.stdout()).equals(normalize(outputRun.stdout()));
    }

    private String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
