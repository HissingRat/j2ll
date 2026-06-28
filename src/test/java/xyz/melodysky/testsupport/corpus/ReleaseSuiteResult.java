package xyz.melodysky.testsupport.corpus;

import java.nio.file.Path;
import java.util.List;

public record ReleaseSuiteResult(String name, List<CorpusRunResult> cases, Path summary) {
    public ReleaseSuiteResult {
        cases = List.copyOf(cases);
    }
}
