package com.jenkinschina.atom.logparser;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import com.jenkinschina.atom.sdk.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Scans a build log against named rules and turns the hits into numbers and a verdict.
 *
 * <p>The log-parser plugin polls a running build's log from the master; an atom only sees the log it was handed, so
 * this reads {@code build.log} (or a file in the workspace) once, applies the caller's rules, and reports counts plus
 * the first few offending lines. The interesting part is the rule semantics: a rule that must not match anything, a
 * rule that only warns, and a rule that fails the step.
 */
public class LogParserAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String source = context.getParam("source", "buildLog").trim();
        String rulesJson = context.getParam("rules", "");
        boolean ignoreCase = context.getBooleanParam("ignoreCase", false);
        int maxMatchesPerRule = Math.max(1, Math.min(context.getIntParam("maxMatchesPerRule", 20), 1000));

        if (rulesJson.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "rules is required, e.g. {\"error\": \"ERROR|Exception*\", \"warning\": \"WARN\"}");
        }
        List<Rule> rules = parseRules(rulesJson, ignoreCase);
        if (rules.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "rules contains no usable rule");
        }

        String log;
        if ("buildLog".equalsIgnoreCase(source)) {
            if (!context.hasBuildLog()) {
                return AtomResult.error(ErrorType.USER, 40003,
                        "this build has no log yet (source=buildLog needs a running build); "
                                + "set source to a file in the workspace instead");
            }
            log = context.buildLog();
        } else {
            Path file = workspace.resolve(source).normalize();
            if (!file.startsWith(workspace) || !Files.isRegularFile(file)) {
                return AtomResult.error(ErrorType.USER, 40004, "no log file at " + source);
            }
            log = Files.readString(file, StandardCharsets.UTF_8);
        }

        String[] lines = log.split("\r?\n", -1);
        List<String> failures = new ArrayList<>();
        Map<String, Object> counts = new LinkedHashMap<>();
        Map<String, Object> samples = new LinkedHashMap<>();

        for (Rule rule : rules) {
            List<String> hits = new ArrayList<>();
            int count = 0;
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = rule.pattern().matcher(lines[i]);
                if (!matcher.find()) {
                    continue;
                }
                count++;
                if (rule.reportAll() || count <= maxMatchesPerRule) {
                    hits.add(String.format(Locale.ROOT, "%d: %s", i + 1, truncate(lines[i], 240)));
                }
            }
            counts.put(rule.name(), count);
            if (!hits.isEmpty()) {
                samples.put(rule.name(), hits);
            }
            context.log("[log-parser] " + rule.name() + ": " + count + " match(es)"
                    + (rule.minMatches() > 0 ? " (needs at least " + rule.minMatches() + ")" : "")
                    + (rule.maxMatches() >= 0 ? " (at most " + rule.maxMatches() + ")" : ""));

            String problem = rule.judge(count);
            if (problem != null) {
                String first = hits.isEmpty() ? "" : " first: " + hits.get(0);
                if (rule.error()) {
                    failures.add(problem + first);
                } else {
                    // a warning-level rule states the fact and keeps going, which is what a "tolerated" pattern means
                    context.log("[log-parser] warning: " + problem);
                }
            }
        }

        AtomResult result;
        if (failures.isEmpty()) {
            result = AtomResult.success();
        } else {
            result = AtomResult.error(ErrorType.USER, 40005, String.join("; ", failures));
        }
        result = result.data("rules", String.valueOf(rules.size()));
        for (Map.Entry<String, Object> entry : counts.entrySet()) {
            // output keys become environment variables, and a dot is not legal in one: count_error, not count.error
            result = result.data("count_" + safe(entry.getKey()), String.valueOf(entry.getValue()));
        }
        if (!samples.isEmpty()) {
            result = result.data("matches", Json.write(samples));
        }
        return result;
    }

    /**
     * Rules are {@code {"name": "regex"}} for "must not match", or
     * {@code {"name": {"pattern": "regex", "min": 1, "max": 0, "level": "error", "reportAll": false}}} when the
     * step should say how many are expected.
     */
    private static List<Rule> parseRules(String json, boolean ignoreCase) {
        List<Rule> rules = new ArrayList<>();
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("rules is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("rules must be a JSON object of name -> pattern");
        }
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            String pattern;
            int min = 0;
            int max = 0;
            boolean error = true;
            boolean reportAll = false;
            if (value instanceof Map<?, ?> spec) {
                pattern = spec.get("pattern") == null ? "" : String.valueOf(spec.get("pattern"));
                boolean hasMin = spec.get("min") != null;
                boolean hasMax = spec.get("max") != null;
                min = hasMin ? Integer.parseInt(String.valueOf(spec.get("min"))) : 0;
                // "at least one" must not imply "at most zero": a bound the caller did not state is unbounded
                max = hasMax ? Integer.parseInt(String.valueOf(spec.get("max"))) : (hasMin ? -1 : 0);
                error = spec.get("level") == null || "error".equalsIgnoreCase(String.valueOf(spec.get("level")));
                reportAll = spec.get("reportAll") != null && Boolean.parseBoolean(String.valueOf(spec.get("reportAll")));
            } else {
                pattern = String.valueOf(value);
            }
            if (pattern.isBlank()) {
                throw new IllegalArgumentException("rule '" + name + "' has no pattern");
            }
            try {
                rules.add(new Rule(name, Pattern.compile(pattern, flags), min, max, error, reportAll));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("rule '" + name + "' is not a valid regular expression: "
                        + e.getDescription());
            }
        }
        return rules;
    }


    /** Rule names are chosen by the caller; the output key has to survive being an environment variable. */
    private static String safe(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9_]", "_");
        return cleaned.isEmpty() ? "rule" : cleaned;
    }

    private static String truncate(String value, int max) {
        String flat = value.replaceAll("\\s+$", "");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /**
     * @param error   whether a breached bound fails the step or only warns
     * @param max     the most matches allowed; -1 means unbounded
     */
    private record Rule(String name, Pattern pattern, int minMatches, int maxMatches, boolean error,
                        boolean reportAll) {

        String judge(int count) {
            if (count < minMatches) {
                return name + ": found " + count + ", expected at least " + minMatches;
            }
            if (maxMatches >= 0 && count > maxMatches) {
                return name + ": found " + count + ", allowed at most " + maxMatches;
            }
            return null;
        }
    }
}
