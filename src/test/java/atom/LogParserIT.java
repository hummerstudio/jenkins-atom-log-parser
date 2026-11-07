package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The log-parser atom, started the way the runner starts it.
 *
 * <p>These are the checks {@code tools/smoke.sh} made: a blocking rule with the first offending line, min/max bounds,
 * the warn level, ignoreCase, a bad regex, and the two ways the log itself can be absent.
 */
class LogParserIT {

    private static final String LOG = String.join("\n",
            "[INFO] starting",
            "[INFO] compiling 12 sources",
            "[WARN] deprecated API in Foo.java",
            "[WARN] deprecated API in Bar.java",
            "[ERROR] compilation failed",
            "java.lang.NullPointerException: at Main.run",
            "Tests run: 12, Failures: 1",
            "[INFO] done") + "\n";

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("a blocking rule fires and names the first offending line")
    void blockingRuleNamesTheFirstLine() throws Exception {
        AtomResult run = atom.file("build-output.log", LOG)
                .input("{\"source\": \"build-output.log\", \"rules\": \"{\\\"error\\\": \\\"ERROR|Exception\\\"}\"}")
                .run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("error: found 2, allowed at most 0"), output);
        assertTrue(output.contains("first: 5: [ERROR] compilation failed"), "the first hit with its line number:\n" + output);
        assertEquals("2", run.field("count_error"));
    }

    @Test
    @DisplayName("the matches carry line numbers")
    void matchesCarryLineNumbers() throws Exception {
        AtomResult run = atom.file("build-output.log", LOG)
                .input("{\"source\": \"build-output.log\", \"rules\": \"{\\\"error\\\": \\\"ERROR|Exception\\\"}\"}")
                .run();

        Map<String, Object> matches = Json.parseObject(run.field("matches"));
        @SuppressWarnings("unchecked")
        List<String> error = (List<String>) matches.get("error");
        assertEquals(2, error.size(), String.valueOf(matches));
        assertTrue(error.get(0).startsWith("5: "), String.valueOf(error));
        assertTrue(error.get(1).startsWith("6: "), String.valueOf(error));
    }

    @Test
    @DisplayName("a bound the caller stated is enforced, and a satisfied rule still reports its count")
    void enforcesMinAndMax() throws Exception {
        AtomResult breached = atom.file("build-output.log", LOG)
                .input("{\"source\": \"build-output.log\", \"rules\": \"{\\\"tests\\\": {\\\"pattern\\\": \\\"Tests run:\\\","
                        + " \\\"min\\\": 1}, \\\"npe\\\": {\\\"pattern\\\": \\\"NullPointerException\\\", \\\"max\\\": 0}}\"}")
                .run();

        breached.assertRefused();
        assertTrue(breached.file("output.json").contains("npe: found 1, allowed at most 0"),
                breached.file("output.json"));

        AtomResult satisfied = atom.file("build-output.log", LOG)
                .input("{\"source\": \"build-output.log\", \"rules\": \"{\\\"tests\\\": {\\\"pattern\\\": \\\"Tests run:\\\","
                        + " \\\"min\\\": 1}}\"}")
                .run();

        satisfied.assertOk();
        assertEquals("1", satisfied.field("count_tests"));
    }

    @Test
    @DisplayName("a warning-level rule states the fact and does not fail the build")
    void warnsWithoutFailing() throws Exception {
        AtomResult run = atom.file("build-output.log", LOG)
                .input("{\"source\": \"build-output.log\", \"rules\": \"{\\\"deprecated\\\": {\\\"pattern\\\":"
                        + " \\\"deprecated\\\", \\\"max\\\": 1, \\\"level\\\": \\\"warn\\\"}}\"}")
                .run();

        run.assertOk();
        assertTrue(run.console().contains("warning: deprecated: found 2, allowed at most 1"),
                "the warning belongs in the log:\n" + run.console());
    }

    @Test
    @DisplayName("ignoreCase decides whether a differently-cased line counts")
    void ignoreCaseDecidesTheCount() throws Exception {
        AtomResult sensitive = atom.file("case.log", "[WARN] Deprecated call\n")
                .input("{\"source\": \"case.log\", \"rules\": \"{\\\"deprecated\\\": \\\"deprecated\\\"}\"}")
                .run();
        sensitive.assertOk();

        AtomResult insensitive = atom.file("case.log", "[WARN] Deprecated call\n")
                .input("{\"source\": \"case.log\", \"ignoreCase\": \"true\","
                        + " \"rules\": \"{\\\"deprecated\\\": \\\"deprecated\\\"}\"}")
                .run();

        insensitive.assertRefused();
        assertTrue(insensitive.file("output.json").contains("deprecated: found 1, allowed at most 0"),
                insensitive.file("output.json"));
    }

    @Test
    @DisplayName("a bad regex is reported as a rule problem, not a crash")
    void reportsABadRegex() throws Exception {
        AtomResult run = atom.file("build-output.log", LOG)
                .input("{\"source\": \"build-output.log\", \"rules\": \"{\\\"broken\\\": \\\"([a-z\\\"}\"}")
                .run();

        run.assertRefused();
        String everything = run.file("output.json") + "\n" + run.console();
        assertTrue(everything.toLowerCase().contains("regular expression"),
                "the regex error should be explained:\n" + everything);
    }

    @Test
    @DisplayName("a missing log file is a USER error naming the file")
    void refusesAMissingLog() throws Exception {
        AtomResult run = atom.input("{\"source\": \"nope.log\", \"rules\": \"{\\\"error\\\": \\\"ERROR\\\"}\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no log file at nope.log"), run.file("output.json"));
    }

    @Test
    @DisplayName("source=buildLog without a build log is refused with a reason")
    void refusesWhenThereIsNoBuildLog() throws Exception {
        AtomResult run = atom.withoutEnvironment()
                .input("{\"rules\": \"{\\\"error\\\": \\\"ERROR\\\"}\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no log yet"), run.file("output.json"));
        assertFalse(run.file("output.json").contains("no log file at"), run.file("output.json"));
    }
}
