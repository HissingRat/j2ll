package xyz.melodysky.testsupport.corpus;

import java.util.List;

public record ReleaseSuite(String name, ReleaseSuiteProfile profile, List<CorpusCase> cases) {
    public ReleaseSuite(String name, List<CorpusCase> cases) {
        this(name, ReleaseSuiteProfile.STANDARD, cases);
    }

    public ReleaseSuite {
        if (name.isBlank()) {
            throw new IllegalArgumentException("suite name must not be blank");
        }
        if (profile == null) {
            profile = ReleaseSuiteProfile.STANDARD;
        }
        cases = List.copyOf(cases).stream()
                .sorted(java.util.Comparator.comparing(CorpusCase::name))
                .toList();
    }
}
