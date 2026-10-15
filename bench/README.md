# The benchmark harness

Evidence that the tool is worth using, and, more importantly, that it never skips a test that
matters. The figures it produced are in [docs/benchmarks.md](../docs/benchmarks.md); the fields of
its results files are in [docs/sweep-data-dictionary.md](../docs/sweep-data-dictionary.md).

The headline metric is **missed failures**: tests that fail under a full run and were not selected.
It is the only one that can disqualify the tool, and the only one a green build cannot reveal. Speed
is reported alongside it, never instead of it.

## Running one

```bash
python3 bench/run.py bench/projects/demo.toml
```

The first run clones the project at its pinned commit into `bench/checkouts/` and captures a
coverage map, which costs one instrumented full test run. Every mutation after that costs a selected
run plus a full run for ground truth, so a sweep of a real project is measured in hours. It is
resumable: re-running skips mutations already recorded in the results file.

The plugin under test is built from this repository, or from wherever `YORIWAKE_CHECKOUT` points
(`checkout.py`). Every row is stamped with that commit, and a tree with uncommitted changes outside
`bench/` is refused. Run one measurement at a time on a machine doing nothing else: two at once
measure each other.

## What it does per mutation

1. Apply one mutation to one source file.
2. Run the suite **selected** (`-Pyoriwake.select`) and record what ran.
3. Run the suite **in full**, with the plugin absent, and record what genuinely failed.
4. Compare: every genuine failure must appear in the selected run.

Ground truth comes from step 3 and never from the coverage map, because the map is the artefact under
test.

## What counts as a result, and what does not

Three things are excluded rather than scored, because a measurement that did not happen must never
read as a pass:

- a mutation that does not compile;
- a build that never reached the test task;
- result files older than the run that claims to have produced them.

One thing that *is* scored: a run that reached the tests and selected **zero** of them. That is the
catastrophic case, real failures skipped, and filing it as a build error is how a harness hides the
exact defect it exists to find.

A project file with `sources = []` asks nothing: the mutation grammar finds zero targets and the
sweep would report 0 missed of 0 induced failures. The harness refuses it.

## Which targets a sweep draws

Sorted paths start with `Abstract*`, base classes that are hubs, and a mutation on a hub forces a
full run, which cannot miss anything. Drawn in path order, recall comes back perfect and narrowing
terrible. So targets are split into hub, mid and leaf by the map's own reach counts and taken
round-robin, ordered within a stratum by a seeded SHA-256 of the target. The seed and the ordering's
name are written into every results file (`target_seed`, `target_ordering`), so a sample can be
drawn again.

## What survives the machine

| artefact | what it keeps |
|---|---|
| `bench/results/<project>.json` | recall, narrowing, why each row decided as it did, which map it selected against, and the machine conditions of each arm |
| `bench/qualification.txt` | which projects qualify, regenerated whole by `write_qualification.py` |
| `bench/logs/<project>/decisions-*.tsv` | which tests were selected, one row each with the selector's reason; the results file keeps a digest of the set |
| `bench/logs/<project>/*.log` | the Gradle output of every arm, about a gigabyte per project |
| `bench/checkouts/` | nothing that cannot be re-cloned from the pinned commit |

`results/`, `logs/` and `checkouts/` are not committed. From a results file alone you can ask why a
row ran everything (`full_run_kind`, `decision_reason`), whether two rows selected the same tests
(`selected_digest`), which map they used (`map_capture_commit`, `map_records`) and what the machine
was doing at each arm (`selected_conditions`, `full_conditions`). Listing the selected tests or
reading a stack trace needs the logs of the machine that ran the sweep.

## The other instruments

- `sweep_corpus.py`: `run.py` over every qualified project, one at a time, and what each answered.
- `qualify.py <project.toml>`: does it build, run tests hermetically, attach the plugin, and would
  it narrow? `write_qualification.py` collates the answers into `qualification.txt`.
- `controls.py`: sweeps small fixtures against a plugin built to miss, and fails unless the harness
  reports the miss.
- `replay_decisions.py`: asks the current selector every recall question a sweep already paid
  for, in seconds and offline.
- `config_cost.py`: what applying the plugin costs a project before it runs a test.
- `cost.py`: what a sweep will cost before it starts, and what it cost after.
- `corpus_state.py`: per project, whether its recall evidence is clean, and if not, what it is.
- `readme_charts.py`: the benchmark charts, as SVG, from results files.

Each `test_*.py` beside them is that module's tests: `python3 bench/test_run.py`.

## Adding a project

Create `bench/projects/<name>.toml`:

```toml
name = "detekt"
url = "https://github.com/detekt/detekt.git"
commit = "6aa07c067e181a72700c90f893c02ae91ce56676"
module = "detekt-rules-style"
# Extra flags the project needs. detekt ships its own predictive test selection, which would thin
# the baseline and flatter the numbers.
flags = ["-PenablePTS=false"]
# Where mutations are drawn from, relative to the checkout. Never empty, and scoped to the module
# under measurement: a mutation outside it cannot break a test the measured task runs.
sources = ["detekt-rules-style/src/main/kotlin", "detekt-psi-utils/src/main/kotlin"]
```

Then qualify it. Choose projects for shape rather than fame: a green suite and a red one, Java and
Kotlin, parameterised-heavy and plain. Anything needing network services is out: the sweep must run
unattended.
