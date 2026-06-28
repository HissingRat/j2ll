package xyz.melodysky.testsupport.corpus;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record CorpusReportPaths(Map<String, Path> reports) {
    public CorpusReportPaths {
        reports = Collections.unmodifiableMap(new LinkedHashMap<>(reports));
    }
}
