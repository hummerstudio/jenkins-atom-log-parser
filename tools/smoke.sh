#!/usr/bin/env bash
# Rule semantics, counts, line numbers, and the three ways a rule can be wrong.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

ws="$(mktemp -d)"
write_input() { printf '%s' "$1" >"$ws/input.json"; }

cat >"$ws/build-output.log" <<'LOG'
[INFO] starting
[INFO] compiling 12 sources
[WARN] deprecated API in Foo.java
[WARN] deprecated API in Bar.java
[ERROR] compilation failed
java.lang.NullPointerException: at Main.run
Tests run: 12, Failures: 1
[INFO] done
LOG

# --- a blocking rule fires, and the failure names the first offending line
write_input '{"source": "build-output.log", "rules": "{\"error\": \"ERROR|Exception\"}"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "an ERROR line must fail the step"
grep -q "error: found 2, allowed at most 0" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"
grep -q "first: 5: \[ERROR\] compilation failed" "$ws/output.json" || fail "the first hit with its line number: $(cat "$ws/output.json")"
[ "$(output "$ws" count_error)" = "2" ] || fail "count: $(cat "$ws/output.json")"

# --- the matches carry line numbers
python3 - "$ws/output.json" <<'PY' || fail "matches should carry line numbers"
import json, sys
doc = json.load(open(sys.argv[1]))["data"]["matches"]["value"]
matches = json.loads(doc)["error"]
assert matches[0].startswith("5: "), matches
assert len(matches) == 2, matches
PY

# --- a rule that is satisfied passes and still reports its count
write_input '{"source": "build-output.log", "rules": "{\"tests\": {\"pattern\": \"Tests run:\", \"min\": 1}, \"npe\": {\"pattern\": \"NullPointerException\", \"max\": 0}}"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "an NPE line must fail when max is 0"
grep -q "npe: found 1, allowed at most 0" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"
write_input '{"source": "build-output.log", "rules": "{\"tests\": {\"pattern\": \"Tests run:\", \"min\": 1}}"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "a satisfied rule must pass: $(cat "$ws/output.json")"
[ "$(output "$ws" count_tests)" = "1" ] || fail "count.tests: $(cat "$ws/output.json")"

# --- a warning-level rule states the fact and does not fail the build
write_input '{"source": "build-output.log", "rules": "{\"deprecated\": {\"pattern\": \"deprecated\", \"max\": 1, \"level\": \"warn\"}}"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "a warn-level breach must not fail: $(cat "$ws/output.json")"
grep -q "warning: deprecated: found 2, allowed at most 1" "$ws/atom.log" || fail "the warning should be in the log"

# --- ignoreCase decides whether "Deprecated" counts
printf '[WARN] Deprecated call\n' >"$ws/case.log"
write_input '{"source": "case.log", "rules": "{\"deprecated\": \"deprecated\"}"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "case-sensitive should not match"
write_input '{"source": "case.log", "ignoreCase": "true", "rules": "{\"deprecated\": \"deprecated\"}"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "ignoreCase should match"
grep -q "deprecated: found 1, allowed at most 0" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

# --- a bad regex is reported as a rule problem, not a crash
write_input '{"source": "build-output.log", "rules": "{\"broken\": \"([a-z\"}"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "an invalid regex must fail"
grep -qi "not a valid regular expression" "$ws/output.json" || grep -qi "regular expression" "$ws/atom.log" \
  || fail "the regex error should be explained: $(cat "$ws/output.json")"

# --- a missing log file is a USER error
write_input '{"source": "nope.log", "rules": "{\"error\": \"ERROR\"}"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "a missing log must fail"
grep -q "no log file at nope.log" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

# --- buildLog without a log is refused with a reason
write_input '{"rules": "{\"error\": \"ERROR\"}"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "no build log must fail"
grep -q "no log yet" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

echo "smoke ok: blocking rule with line numbers, min/max rules, warn level, ignoreCase, bad regex, missing log, no build log"
