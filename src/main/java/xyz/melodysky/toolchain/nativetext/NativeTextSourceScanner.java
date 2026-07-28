package xyz.melodysky.toolchain.nativetext;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural scanner for reusable native-text extraction shapes.
 *
 * <p>The checks intentionally rely on function/data-flow shape as well as
 * production identifiers. Renaming the historical decoder is not enough to
 * evade fanout, fixed-SplitMix or adjacent seed-share detection.</p>
 */
public final class NativeTextSourceScanner {
    private static final String SPLITMIX_WEYL = "9e3779b97f4a7c15";
    private static final String SPLITMIX_MIX0 = "bf58476d1ce4e5b9";
    private static final String SPLITMIX_MIX1 = "94d049bb133111eb";
    private static final int FIXED_SHAPE_WINDOW = 1800;

    private static final Pattern CIPHER_DECLARATION = Pattern.compile(
            "\\bstatic\\s+(?:const\\s+)?unsigned\\s+char\\s+"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s*\\[[^]]*]\\s*=\\s*\\{");
    private static final Pattern PRODUCTION_CIPHER = Pattern.compile(
            "\\bj2ll_nt_[0-9a-f]{24}_cipher\\b");
    private static final Pattern RUNTIME_BOUND_CIPHER_READ = Pattern.compile(
            "\\(\\(\\s*const\\s+volatile\\s+unsigned\\s+char\\s*\\*\\s*\\)"
                    + "\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)\\s*\\)"
                    + "\\s*\\[");
    private static final Pattern UNBOUNDED_PRODUCTION_CIPHER_ACCESS =
            Pattern.compile(
                    "\\b(j2ll_nt_[0-9a-f]{24}_cipher)\\s*"
                            + "\\[(?!\\s*\\])");
    private static final Pattern SITE_CODEC = Pattern.compile(
            "\\bj2ll_nt_word_([0-9a-f]{24})\\b");
    private static final Map<Pattern, NativeTextCodecFamily> FAMILY_MARKERS =
            familyMarkers();
    private static final Pattern FUNCTION_DEFINITION = Pattern.compile(
            "\\bstatic\\s+(?:inline\\s+)?"
                    + "(?:void|int|unsigned\\s+char|uint64_t|size_t)\\s+"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s*"
                    + "\\(([^;{}]*)\\)\\s*\\{");
    private static final Pattern LOOP = Pattern.compile("\\b(?:for|while)\\s*\\(");
    private static final Pattern POINTER_PARAMETER = Pattern.compile("\\*");
    private static final Pattern ADJACENT_SEED_SHARES = Pattern.compile(
            "(?is)\\b(?:seed|key)[A-Za-z0-9_]*\\s*=\\s*"
                    + "UINT64_C\\s*\\(\\s*0x[0-9a-f]+\\s*\\)\\s*\\^\\s*"
                    + "UINT64_C\\s*\\(\\s*0x[0-9a-f]+\\s*\\)");
    private static final Pattern CIPHER_NEAR_CONSTANT_XOR = Pattern.compile(
            "(?is)(?:cipher|encoded)[A-Za-z0-9_\\s\\[\\]()*+,.-]{0,320}"
                    + "UINT64_C\\s*\\(\\s*0x[0-9a-f]+\\s*\\)\\s*\\^\\s*"
                    + "UINT64_C\\s*\\(\\s*0x[0-9a-f]+\\s*\\)"
                    + "|UINT64_C\\s*\\(\\s*0x[0-9a-f]+\\s*\\)\\s*\\^\\s*"
                    + "UINT64_C\\s*\\(\\s*0x[0-9a-f]+\\s*\\)"
                    + "[A-Za-z0-9_\\s\\[\\]()*+,.-]{0,320}(?:cipher|encoded)");

    public NativeTextSourceMetrics scan(String source) {
        Objects.requireNonNull(source, "source");
        String structural = NativeTextCSourceMasker.spliceLineContinuations(
                NativeTextCSourceMasker.maskNonCode(source));
        Set<String> ciphers = matches(CIPHER_DECLARATION, structural, 1);
        Set<String> productionCiphers = ciphers.stream()
                .filter(cipher -> PRODUCTION_CIPHER.matcher(cipher).matches())
                .collect(java.util.stream.Collectors.toSet());
        int cipherArrayCount = productionCiphers.size();
        Set<String> runtimeBoundCipherReads =
                matches(RUNTIME_BOUND_CIPHER_READ, structural, 1);
        Set<String> unboundedCipherAccesses =
                matches(UNBOUNDED_PRODUCTION_CIPHER_ACCESS, structural, 1);
        int runtimeBoundCipherReadCount = (int) productionCiphers.stream()
                .filter(runtimeBoundCipherReads::contains)
                .filter(cipher -> !unboundedCipherAccesses.contains(cipher))
                .count();
        int firstMissingRuntimeBoundCipherOffset =
                firstMatchOffset(
                        UNBOUNDED_PRODUCTION_CIPHER_ACCESS,
                        structural);
        if (firstMissingRuntimeBoundCipherOffset < 0) {
            firstMissingRuntimeBoundCipherOffset = productionCiphers.stream()
                .filter(cipher -> !runtimeBoundCipherReads.contains(cipher))
                .mapToInt(structural::indexOf)
                .filter(offset -> offset >= 0)
                .min()
                .orElse(-1);
        }
        Set<String> codecSites = matches(SITE_CODEC, structural, 1);
        Set<NativeTextCodecFamily> families = new HashSet<>();
        for (Map.Entry<Pattern, NativeTextCodecFamily> marker
                : FAMILY_MARKERS.entrySet()) {
            if (marker.getKey().matcher(structural).find()) {
                families.add(marker.getValue());
            }
        }

        DecoderFacts decoderFacts = decoderFacts(structural, ciphers);
        OffsetCount fixed = fixedShapeOccurrences(structural);
        OffsetCount adjacent = adjacentSeedOccurrences(structural);
        return new NativeTextSourceMetrics(
                cipherArrayCount,
                runtimeBoundCipherReadCount,
                codecSites.size(),
                families.size(),
                decoderFacts.decoderCount(),
                decoderFacts.largestFanout(),
                fixed.count(),
                adjacent.count(),
                firstMissingRuntimeBoundCipherOffset,
                decoderFacts.firstFanoutOffset(),
                fixed.firstOffset(),
                adjacent.firstOffset());
    }

    private DecoderFacts decoderFacts(
            String source,
            Set<String> cipherArrays) {
        int decoderCount = 0;
        int largestFanout = 0;
        int firstFanoutOffset = -1;
        Matcher functions = FUNCTION_DEFINITION.matcher(source);
        while (functions.find()) {
            int opening = source.indexOf('{', functions.start());
            int closing = matchingBrace(source, opening);
            if (closing < 0) {
                continue;
            }
            String parameters = functions.group(2);
            String body = source.substring(opening, closing + 1);
            if (!looksLikeReusableDecoder(parameters, body)
                    && !looksLikePersistentDecoder(parameters, body)) {
                continue;
            }
            decoderCount++;
            Set<String> decoded = referencedCiphers(body, cipherArrays);
            if (looksLikeReusableDecoder(parameters, body)) {
                decoded.addAll(callSiteCiphers(
                        source,
                        functions.group(1),
                        closing + 1,
                        cipherArrays));
            }
            if (decoded.size() > largestFanout) {
                largestFanout = decoded.size();
                if (largestFanout >= 2 && firstFanoutOffset < 0) {
                    firstFanoutOffset = functions.start();
                }
            }
        }
        return new DecoderFacts(
                decoderCount,
                largestFanout,
                firstFanoutOffset);
    }

    private boolean looksLikeReusableDecoder(
            String parameters,
            String body) {
        return count(POINTER_PARAMETER, parameters) >= 2
                && LOOP.matcher(body).find()
                && body.indexOf('^') >= 0
                && body.contains("unsigned char")
                && !SITE_CODEC.matcher(body).find()
                && !containsJniUse(body);
    }

    private boolean looksLikePersistentDecoder(
            String parameters,
            String body) {
        return parameters.trim().equals("void")
                && LOOP.matcher(body).find()
                && body.indexOf('^') >= 0
                && body.contains("unsigned char")
                && !SITE_CODEC.matcher(body).find()
                && !containsJniUse(body);
    }

    private boolean containsJniUse(String body) {
        return body.contains("RegisterNatives")
                || body.contains("NewStringUTF")
                || body.contains("FindClass")
                || body.contains("GetMethodID")
                || body.contains("GetFieldID");
    }

    private Set<String> referencedCiphers(
            String body,
            Set<String> cipherArrays) {
        HashSet<String> referenced = new HashSet<>();
        for (String cipher : cipherArrays) {
            if (identifierPresent(body, cipher)) {
                referenced.add(cipher);
            }
        }
        return referenced;
    }

    private Set<String> callSiteCiphers(
            String source,
            String function,
            int definitionEnd,
            Set<String> cipherArrays) {
        HashSet<String> referenced = new HashSet<>();
        Matcher calls = Pattern.compile(
                        "\\b" + Pattern.quote(function) + "\\s*\\(([^;{}]*)\\)\\s*;")
                .matcher(source);
        while (calls.find()) {
            if (calls.start() < definitionEnd) {
                continue;
            }
            referenced.addAll(referencedCiphers(
                    calls.group(1),
                    cipherArrays));
        }
        return referenced;
    }

    private OffsetCount fixedShapeOccurrences(String source) {
        String lower = source.toLowerCase(java.util.Locale.ROOT);
        int count = 0;
        int first = -1;
        int offset = 0;
        while ((offset = lower.indexOf(SPLITMIX_WEYL, offset)) >= 0) {
            int end = Math.min(lower.length(), offset + FIXED_SHAPE_WINDOW);
            String window = lower.substring(offset, end);
            if (window.contains(SPLITMIX_MIX0)
                    && window.contains(SPLITMIX_MIX1)) {
                count++;
                if (first < 0) {
                    first = offset;
                }
            }
            offset += SPLITMIX_WEYL.length();
        }
        return new OffsetCount(count, first);
    }

    private OffsetCount adjacentSeedOccurrences(String source) {
        int count = 0;
        int first = -1;
        for (Pattern pattern : java.util.List.of(
                ADJACENT_SEED_SHARES,
                CIPHER_NEAR_CONSTANT_XOR)) {
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) {
                count++;
                if (first < 0 || matcher.start() < first) {
                    first = matcher.start();
                }
            }
        }
        return new OffsetCount(count, first);
    }

    private Set<String> matches(
            Pattern pattern,
            String source,
            int group) {
        HashSet<String> values = new HashSet<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            values.add(matcher.group(group));
        }
        return values;
    }

    private int count(Pattern pattern, String source) {
        int count = 0;
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int firstMatchOffset(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.start() : -1;
    }

    private boolean identifierPresent(String source, String identifier) {
        return Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b")
                .matcher(source)
                .find();
    }

    private int matchingBrace(String source, int opening) {
        if (opening < 0) {
            return -1;
        }
        int depth = 0;
        for (int index = opening; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static Map<Pattern, NativeTextCodecFamily> familyMarkers() {
        LinkedHashMap<Pattern, NativeTextCodecFamily> markers =
                new LinkedHashMap<>();
        markers.put(
                Pattern.compile("\\bj2ll_nt_w_[0-9a-f]{24}\\b"),
                NativeTextCodecFamily.WEYL_ARX);
        markers.put(
                Pattern.compile("\\bj2ll_nt_d0_[0-9a-f]{24}\\b"),
                NativeTextCodecFamily.DUAL_LANE_ARX);
        markers.put(
                Pattern.compile("\\bj2ll_nt_fl_[0-9a-f]{24}\\b"),
                NativeTextCodecFamily.FEISTEL_32);
        markers.put(
                Pattern.compile("\\bj2ll_nt_r_[0-9a-f]{24}\\b"),
                NativeTextCodecFamily.FOLD_ROTATE);
        return Map.copyOf(markers);
    }

    private record DecoderFacts(
            int decoderCount,
            int largestFanout,
            int firstFanoutOffset) {}

    private record OffsetCount(int count, int firstOffset) {}

}
