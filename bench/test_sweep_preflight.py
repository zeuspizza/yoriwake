"""Guards for the sweep pre-flight: each check must refuse on the state it exists for.

Cases return a count of failed checks rather than asserting, so a function that asserted cannot be
collected by pytest and pass however many checks failed.

    python bench/test_sweep_preflight.py
"""

import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import sweep_preflight  # noqa: E402

GIB = 2 ** 30


def fail(message):
    print(f"  FAIL: {message}")
    return 1


def case_the_disk_need_is_three_logs_two_maps_and_their_evidence():
    need = sweep_preflight.disk_need(10 * GIB, 4 * GIB)
    expected = 30 * GIB + 8 * GIB + int(0.12 * 2 * 14 * GIB)
    return 0 if need == expected else fail(f"need {need / GIB:.2f} GiB, expected {expected / GIB:.2f}")


def case_an_only_leg_is_priced_from_its_own_projects():
    problems = 0
    command = ["python3", "bench/sweep_corpus.py", "--recapture", "--only", "a", "b", "--limit", "4"]
    if sweep_preflight.only_projects(command) != ["a", "b"]:
        problems += fail(f"--only read as {sweep_preflight.only_projects(command)}")
    if sweep_preflight.only_projects(["python3", "bench/sweep_corpus.py"]) is not None:
        problems += fail("a corpus sweep was read as an --only leg")
    with tempfile.TemporaryDirectory() as root:
        logs, checkouts = os.path.join(root, "logs"), os.path.join(root, "checkouts")
        for directory, size in (("a", 3), ("a.isolated", 2), ("b", 1), ("c", 100)):
            os.makedirs(os.path.join(logs, directory))
            with open(os.path.join(logs, directory, "x.log"), "wb") as handle:
                handle.write(b"x" * size)
        for name, size in (("a", 5), ("c", 50)):
            os.makedirs(os.path.join(checkouts, name, ".gradle", "yoriwake"))
            with open(os.path.join(checkouts, name, ".gradle", "yoriwake", "m"), "wb") as handle:
                handle.write(b"m" * size)
        if sweep_preflight.logs_bytes(logs, ["a", "b"]) != 6:
            problems += fail(f"--only logs {sweep_preflight.logs_bytes(logs, ['a', 'b'])}, expected 6")
        if sweep_preflight.logs_bytes(logs) != 106:
            problems += fail(f"corpus logs {sweep_preflight.logs_bytes(logs)}, expected 106")
        if sweep_preflight.map_dirs_bytes(checkouts, ["a", "b"]) != 5:
            problems += fail(f"--only maps {sweep_preflight.map_dirs_bytes(checkouts, ['a', 'b'])}")
        if sweep_preflight.map_dirs_bytes(checkouts) != 55:
            problems += fail(f"corpus maps {sweep_preflight.map_dirs_bytes(checkouts)}, expected 55")
    return problems


def case_battery_and_unknown_power_are_not_mains():
    problems = 0
    ac = "Now drawing from 'AC Power'\n -InternalBattery-0 (id=1)\t100%; charged;\n"
    battery = "Now drawing from 'Battery Power'\n -InternalBattery-0 (id=1)\t80%; discharging;\n"
    if sweep_preflight.on_mains(ac, "Darwin") is not True:
        problems += fail("mains power was not recognised")
    if sweep_preflight.on_mains(battery, "Darwin") is not False:
        problems += fail("battery power was read as mains")
    if sweep_preflight.on_mains(None, "Darwin") is not None:
        problems += fail("an unanswered pmset was read as an answer")
    if sweep_preflight.on_mains("", "Windows") is not None:
        problems += fail("a platform this cannot ask was given an answer")
    return problems


def case_the_checkout_must_be_clean_and_at_the_commit():
    problems = 0
    with tempfile.TemporaryDirectory() as root:
        git = ["git", "-C", root, "-c", "user.name=t", "-c", "user.email=t@t", "-c",
               "commit.gpgsign=false"]
        subprocess.run(["git", "init", "-q", root], check=True)
        with open(os.path.join(root, "a"), "w", encoding="utf-8") as handle:
            handle.write("1\n")
        subprocess.run(git + ["add", "a"], check=True)
        subprocess.run(git + ["commit", "-qm", "one"], check=True)
        first = subprocess.run(git + ["rev-parse", "HEAD"], capture_output=True,
                               text=True).stdout.strip()
        if sweep_preflight.checkout_problem(root, first) is not None:
            problems += fail("a clean checkout at the commit was refused")
        with open(os.path.join(root, "b"), "w", encoding="utf-8") as handle:
            handle.write("untracked\n")
        if sweep_preflight.checkout_problem(root, first) is None:
            problems += fail("an untracked file did not make the checkout dirty")
        os.remove(os.path.join(root, "b"))
        with open(os.path.join(root, "a"), "w", encoding="utf-8") as handle:
            handle.write("2\n")
        subprocess.run(git + ["commit", "-qam", "two"], check=True)
        if sweep_preflight.checkout_problem(root, first) is None:
            problems += fail("a checkout at another commit was accepted")
        if sweep_preflight.checkout_problem(root, "0" * 40) is None:
            problems += fail("a commit the checkout does not have was accepted")
    return problems


def case_another_measurement_is_seen():
    listing = ("  1 /usr/bin/java -cp gradle-daemon-main.jar org.gradle.launcher.daemon.bootstrap."
               "GradleDaemon 8.14\n  2 python bench/run.py bench/projects/demo.toml\n"
               "  3 python bench/sweep_preflight.py --commit x\n  4 /bin/zsh\n")
    running = sweep_preflight.measuring(listing)
    # The daemon is judged by ownership, below, not by name.
    return 0 if len(running) == 1 and "run.py" in running[0] else fail(f"expected run.py, got {running}")


OURS = "/work/harness"
DAEMON = "java -cp gradle-daemon-main.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14"
PROCESSES = [
    (10, 1, DAEMON),                                                   # ours, by its cwd
    (11, 10, "java -cp w.jar worker.org.gradle.process.internal.worker.GradleWorkerMain"),
    (12, 10, "java org.jetbrains.kotlin.daemon.KotlinCompileDaemon"),  # ours, by its parent
    (20, 1, DAEMON),                                                   # another project's
    (21, 20, "java org.jetbrains.kotlin.daemon.KotlinCompileDaemon"),
    (30, 1, "java org.jetbrains.kotlin.daemon.KotlinCompileDaemon"),   # orphaned: no one's
    (31, 1, DAEMON + " -Dorg.gradle.internal.worker.tmpdir=" + OURS + "/bench/checkouts/x/build"),
    (40, 1, "/bin/zsh"),
]
CWDS = {10: OURS + "/bench/checkouts/okhttp", 11: OURS + "/bench/checkouts/okhttp",
        12: "/opt/me/Library/Application Support/kotlin/daemon", 20: "/work/other-project",
        21: "/opt/me/Library/Application Support/kotlin/daemon", 30: "/tmp",
        31: "/opt/me/.gradle/daemon/8.14"}


def case_only_the_harness_s_own_gradle_processes_are_its():
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import own_processes  # noqa: PLC0415
    ours, foreign = own_processes.split(PROCESSES, CWDS, [OURS])
    problems = 0
    if sorted(pid for pid, _ in ours) != [10, 11, 12, 31]:
        problems += fail(f"ours were {sorted(pid for pid, _ in ours)}, expected 10, 11, 12, 31")
    if sorted(pid for pid, _ in foreign) != [20, 21, 30]:
        problems += fail(f"another project's were {sorted(pid for pid, _ in foreign)}, expected 20, 21, 30")
    # A sibling directory sharing the harness's name as a prefix is not the harness.
    ours, _ = own_processes.split([(50, 1, DAEMON)], {50: OURS + "-other/app"}, [OURS])
    if ours:
        problems += fail("a daemon in a sibling directory with the harness's name as prefix was claimed")
    return problems


def case_an_idle_daemon_belongs_to_the_last_build_its_log_records():
    import own_processes  # noqa: PLC0415
    failures = 0
    log = ("... Received command: Build{id=a, currentDir=" + OURS + "/bench/checkouts/okhttp}.\n"
           "... Received command: Build{id=b, currentDir=/work/other-project}.\n")
    if own_processes.last_build_dir(log) != "/work/other-project":
        failures += fail(f"the last build was misread: {own_processes.last_build_dir(log)}")
    idle = {60: "/opt/me/.gradle/daemon/8.14", 61: "/opt/me/.gradle/daemon/8.14"}
    processes = [(60, 1, DAEMON), (61, 1, DAEMON), (62, 60, "java org.jetbrains.kotlin.daemon.KotlinCompileDaemon")]
    last = {60: OURS + "/bench/checkouts/okhttp", 61: "/work/other-project"}
    ours, _ = own_processes.split(processes, idle, [OURS], last)
    if sorted(pid for pid, _ in ours) != [60, 62]:
        failures += fail(f"idle daemons were claimed by their cwd, not their last build: "
                         f"{sorted(pid for pid, _ in ours)}")
    return failures


def case_a_reap_signals_only_the_harness_s_own_daemons():
    import own_processes  # noqa: PLC0415
    signalled = []
    real = own_processes.split
    own_processes.split = lambda: real(PROCESSES, CWDS, [OURS])
    try:
        own_processes.reap_own(kill=lambda pid, _sig: signalled.append(pid))
    finally:
        own_processes.split = real
    return 0 if sorted(signalled) == [10, 11, 12, 31] else fail(f"signalled {sorted(signalled)}")


def case_other_heavy_load_is_named_but_does_not_refuse():
    problems = 0
    listing = " 350.0  901 /usr/bin/java other-project-build\n   2.0  902 /bin/zsh\n"
    warnings = sweep_preflight.busy(8.0, 10, listing)
    if not any("load average 8.0" in w for w in warnings):
        problems += fail(f"a load of 8 on 10 cores was not named: {warnings}")
    if not any("other-project-build" in w for w in warnings) or any("zsh" in w for w in warnings):
        problems += fail(f"the busy process was not the one named: {warnings}")
    if sweep_preflight.busy(1.0, 10, "   2.0  902 /bin/zsh\n"):
        problems += fail("an idle machine was warned about")
    return problems


def case_sleep_is_prevented_where_it_can_be():
    guard = sweep_preflight.sleep_guard("Darwin")
    if sys.platform == "darwin" and (not guard or guard[0] != "caffeinate"):
        return fail(f"no caffeinate guard on macOS: {guard}")
    if sweep_preflight.sleep_guard("Windows") is not None:
        return fail("a sleep guard was claimed on a platform with none")
    return 0


def test_sweep_preflight():
    failures = 0
    for name, case in sorted(globals().items()):
        if name.startswith("case_"):
            print(name)
            failures += case()
    assert failures == 0, f"{failures} failed checks"


if __name__ == "__main__":
    test_sweep_preflight()
    print("\nall checks passed")
