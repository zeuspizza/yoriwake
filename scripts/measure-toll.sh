#!/usr/bin/env bash
#
# Times one instrumented and one uninstrumented full run, then asks yoriwakeAudit what they mean.
#
# A script rather than part of the task: a nested `./gradlew` on the same project waits for locks
# the outer build holds, so the runs are orchestrated here and the plugin keeps the arithmetic.
#
# The uninstrumented run uses `-Pyoriwake.disabled`, which leaves the build entirely alone. The
# instrumented run goes first, so any warm-up bias inflates the toll rather than hiding it.
#
# Usage:
#   scripts/measure-toll.sh <project-dir> <test-task> [extra gradle args...]
#   scripts/measure-toll.sh . :core:test
#   scripts/measure-toll.sh ../my-project :core:test -I "$PWD/scripts/yoriwake.init.gradle.kts"
#
# The extra arguments go to both runs and to the audit, so a build that gets the plugin through
# the init script can be measured without editing it.
set -eu

if [ "$#" -lt 2 ]; then
    echo "usage: $0 <project-dir> <test-task> [extra gradle args...]" >&2
    exit 64
fi

project="$1"
task="$2"
shift 2
extra=("$@")
wrapper="$project/gradlew"
[ -x "$wrapper" ] || wrapper="$project/gradlew.bat"
if [ ! -f "$wrapper" ]; then
    echo "no Gradle wrapper under $project" >&2
    exit 66
fi

# The audit task lives in the test task's project, so the module path has to be carried over.
simple="${task##*:}"
prefix="${task%:*}"
[ "$prefix" = "$task" ] && prefix=""
audit="$prefix:yoriwakeAudit$(printf '%s' "${simple:0:1}" | tr '[:lower:]' '[:upper:]')${simple:1}"
log_dir="${TMPDIR:-/tmp}/yoriwake-toll-$$"
mkdir -p "$log_dir"

timed_run() {
    local label="$1"; shift
    local started ended
    echo "[toll] $label run: $task" >&2
    started=$(date +%s.%N)
    if ! "$wrapper" -p "$project" "$task" --rerun-tasks ${extra+"${extra[@]}"} "$@"             > "$log_dir/$label.log" 2>&1; then
        # A non-zero exit is not a failed measurement: a red suite still ran. Only a suite that did
        # not run (compile error, missing task, killed daemon) invalidates the run.
        if ! grep -q "There were failing tests" "$log_dir/$label.log"; then
            echo "[toll] the $label run did not run its tests; see $log_dir/$label.log" >&2
            return 1
        fi
        echo "[toll] the $label run had failing tests, which is a timing and not a refusal" >&2
    fi
    ended=$(date +%s.%N)
        # LC_ALL=C: `%f` follows the locale, and a decimal comma is unparseable to the plugin.
    LC_ALL=C awk -v a="$started" -v b="$ended" 'BEGIN { printf "%.1f", b - a }'
}

instrumented=$(timed_run instrumented) || exit 70
uninstrumented=$(timed_run uninstrumented -Pyoriwake.disabled) || exit 70

# Assert the flag took effect: if the uninstrumented run was instrumented, the two come out equal
# and the audit refuses for the wrong reason.
if ! grep -q "disabled; not configured" "$log_dir/uninstrumented.log"; then
    echo "[toll] NO CONCLUSION: the uninstrumented run never reported '[yoriwake] ... disabled; not" >&2
    echo "       configured', so -Pyoriwake.disabled did not take effect and that run was instrumented" >&2
    echo "       too. Comparing it against the other one would measure nothing. See" >&2
    echo "       $log_dir/uninstrumented.log" >&2
    exit 71
fi

# The mirror check: a plugin that never attached makes the instrumented run uninstrumented.
# `Task.getPath()` is always colon-prefixed, so a bare `test` needs one added before grepping.
case "$task" in
    :*) task_path="$task" ;;
    *)  task_path=":$task" ;;
esac
if ! grep -q "\[yoriwake\] $task_path map=" "$log_dir/instrumented.log"; then
    echo "[toll] NO CONCLUSION: the instrumented run never printed '[yoriwake] $task_path map=...', so" >&2
    echo "       the plugin did not attach to that task and this run was not instrumented either." >&2
    echo "       Comparing the two would measure nothing. See $log_dir/instrumented.log" >&2
    # Its own code, so a caller can tell this refusal apart from the ones that exit 72.
    exit 73
fi

# Assert both runs did the same work: a flaky suite that fails different tests in each run makes
# the timings incomparable, since a failure can cost a minute. Gradle prints the failure summary
# only for a failed task, so two green runs compare equal without checking anything; that case is
# reported as unverified below. Sorted, because task order can differ between runs.
outcome() {
    sed 's/\x1b\[[0-9;]*m//g' "$log_dir/$1.log" |
        grep -E '^[0-9]+ tests completed|FAILED$' | sort | tr '\n' ';'
}
instrumented_outcome=$(outcome instrumented)
uninstrumented_outcome=$(outcome uninstrumented)
if [ "$instrumented_outcome" != "$uninstrumented_outcome" ]; then
    echo "[toll] NO CONCLUSION: the two runs did not run the same work." >&2
    echo "         instrumented: ${instrumented_outcome:-no summary line}" >&2
    echo "       uninstrumented: ${uninstrumented_outcome:-no summary line}" >&2
    echo "       A flaky suite fails a different set each run, and a failure is not free -- one" >&2
    echo "       can cost a minute. Timing two different workloads against each other" >&2
    echo "       measures the flake. Re-run, or pick a suite that is green." >&2
    echo "       Runs: ${instrumented}s instrumented, ${uninstrumented}s uninstrumented." >&2
    echo "       Logs: $log_dir" >&2
    exit 72
fi

# A red run with no readable summary: refuse rather than compare two blanks.
for side in instrumented uninstrumented; do
    if grep -q "There were failing tests" "$log_dir/$side.log" && [ -z "$(outcome "$side")" ]; then
        echo "[toll] NO CONCLUSION: the $side run reported failing tests but produced no summary" >&2
        echo "       line this could read, so the two runs cannot be compared. See $log_dir." >&2
        exit 72
    fi
done

if [ -z "$instrumented_outcome" ]; then
    echo "[toll] both runs are green, so the same-work check had no failure signature to compare" >&2
    echo "       and did NOT verify that the two runs ran the same tests." >&2
fi

echo "[toll] instrumented ${instrumented}s, uninstrumented ${uninstrumented}s" >&2
# The extra arguments reach the audit too, or a build applying the plugin via `-I` has no audit
# task.
exec "$wrapper" -p "$project" "$audit" \
    -Pyoriwake.audit.measureToll \
    -Pyoriwake.audit.instrumentedSeconds="$instrumented" \
    -Pyoriwake.audit.uninstrumentedSeconds="$uninstrumented" \
    ${extra+"${extra[@]}"}
