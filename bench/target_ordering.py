"""
The order the sweep mutates things in, and the classification behind it.

Split out of `bench/run.py` when that file crossed a thousand lines. The seam is the one the
tests already drew: `bench/test_run.py` imports exactly this set of functions and nothing else
from `run`, because these are the only ones that can be exercised without a checkout, a Gradle
daemon or a subprocess. Everything here is a pure function of a target list, a source tree and a
coverage map on disk.

A pure move, with one behaviour fix recorded separately: see `by_coverage` on Kotlin file classes.
"""

import glob
import hashlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

sys.path.insert(0, HERE)
from recall_check import targets_in  # noqa: E402  (shares the mutation grammar)
sys.path.insert(0, HERE)
import mutations  # noqa: E402


# THE DEFAULT GRAMMAR, and it does not grow.
#
# Every corpus savings and recall figure this repository has published was drawn from this
# population. Adding a shape to it silently changes what every future sweep samples and makes
# figures taken before and after incomparable -- that bias has already been seen
# running the other way, at 5 of 16 kafka targets changing a constant against a real
# history that does so far less often. A new shape goes in a named set the caller asks for.
DEFAULT_SHAPES = "default"

# The recall arm for the digest rule, selected explicitly and never by default.
#
# Three shapes the default grammar cannot produce, each one a CONSUMER-RECOMPILE case: a declaration
# the compiler copies into, or names from, somewhere a change set derived from `git diff` never
# mentions. `constant` is the only default shape that reaches this at all, and it reaches it only
# when the constant happens to be one a test asserts on.
RECALL_ARM_SHAPES = "recall-arm"

SHAPE_SETS = (DEFAULT_SHAPES, RECALL_ARM_SHAPES)


def mutation_targets(checkout, project, shapes=DEFAULT_SHAPES):
    """Every (path, kind, index) worth mutating, across the named shape set.

    The original corpus was one shape -- a Boolean body in a file named after its class -- and it
    reported zero missed failures while the selector was unsound in four ways it could not reach.
    The other three shapes exist because each one defeated the tool in review.

    `shapes` names WHICH grammar. [DEFAULT_SHAPES] is what every published corpus figure was drawn
    from and is left exactly as it was; [RECALL_ARM_SHAPES] is the digest rule's own arm and is
    never mixed into it. A sweep that drew from both would report a number over a population no
    earlier number shares.
    """
    if shapes not in SHAPE_SETS:
        raise ValueError(f"unknown shape set {shapes!r}; expected one of {SHAPE_SETS}")
    # REFUSED, not silently empty, and for the same reason `replay_commits` refuses one layer up: a
    # project that has not said which paths are its own has asked nothing, and a harness that
    # answers a question nobody asked reports 0 missed of 0 as though it were evidence. Six project
    # files carried an empty list and none of them said so anywhere. A print-and-return-empty rather
    # than a raise, because the only caller consumes this directly as a list -- the same shape, and
    # the same precedent, as the refusal in `replay_commits`.
    if not project.get("sources"):
        print(f"    {project.get('name', '?')}: `sources` is empty, so this project names no "
              f"mutation targets and is not swept. Set it to the module's source roots, or record "
              f"why it stays empty.")
        return []

    targets = []
    for source_root in project["sources"]:
        base = os.path.join(checkout, source_root)
        # A single file is a legitimate source root. Directories are the normal case, but asking a
        # recall question about ONE declaration otherwise means running a sweep large enough to draw
        # it by chance -- two hours to reach a file that is known by name. detekt's only inline
        # function lives in one file, and it is the shape behind the inline-selection defect.
        if os.path.isfile(base):
            paths = [base]
        else:
            # KOTLIN AND JAVA ONLY, and a non-empty root holding neither is the gap the refusal above
            # does not cover. `sources = []` is refused; `sources = ["x/src/main/groovy"]` is not, and
            # returns an empty target list -- so the sweep writes a results file with zero rows and
            # `0 missed of 0` reads as a safety result. That is the same "green build that ran
            # nothing" shape, one layer down. It bites any Groovy or Scala module, which is every
            # ecosystem the corpus is trying to reach: grails-core's only source root is Groovy.
            paths = sorted(glob.glob(os.path.join(base, "**", "*.kt"), recursive=True) +
                           glob.glob(os.path.join(base, "**", "*.java"), recursive=True))
        if not paths:
            print(f"    {project.get('name', '?')}: `{source_root}` holds no .java or .kt file, so it "
                  f"yields no mutation targets. A sweep of this project would write zero rows and "
                  f"report 0 missed of 0, which is not a safety result.")
        for path in paths:
            relative = os.path.relpath(path, checkout).replace("\\", "/")
            for index in range(targets_in(path)):
                # `aliased` is the same edit as `body`, applied where the file's name does not name
                # its class -- so the prefix derived from the path names nothing that exists.
                kind = "aliased" if mutations.is_aliased(path) else "body"
                targets.append((relative, kind, index))
            if mutations.has_constant(path):
                targets.append((relative, "constant", 0))
            # Only where there is also something to break. A move alone changes no behaviour, so
            # the pair is what makes the shape answerable: the move creates the dangerous change
            # set, the body mutation gives it a failing test to miss.
            if mutations.can_rename(path) and targets_in(path) > 0:
                targets.append((relative, "renamed", 0))
            # Adding a method changes what an introspecting test sees and nothing an executing one
            # does, so it is the only shape here that can catch a rule narrowing on the absence of
            # coverage. Every other mutation changes behaviour, which coverage records by
            # definition.
            if mutations.has_class_body(path):
                targets.append((relative, "schema", 0))
            if shapes == RECALL_ARM_SHAPES:
                # An inline body copied into every call site: the caller is recompiled and its
                # source does not change, so it is absent from the change set entirely.
                if mutations.has_inline(path):
                    targets.append((relative, "inline", 0))
                # A constant inside a companion object or a nested type. Two of the five known
                # `classesFor` blind spots: the class the value was copied FROM is `Foo$Companion`
                # or `Foo$Nested`, which the changed path does not name.
                if mutations.has_nested_constant(path):
                    targets.append((relative, "nested-constant", 0))
                # A second top-level type in one file. The prefix derived from a changed path names
                # the type the file is named after and nothing else.
                if mutations.has_second_type(path):
                    targets.append((relative, "second-type", 0))
                # SIX SHAPES WERE NAMED AND THREE ARE ADDED HERE, which is not three missing.
                # "A constant read directly by a test" is the existing `constant` shape, and "a
                # `FooKt` facade" is the existing `body` shape applied to a Kotlin top-level
                # function -- those compile into exactly the `FooKt` class the shape is named for.
                # The arm is a SUPERSET of the default grammar, so both are already drawn. Written
                # down because the next reader will otherwise count three against six and add two
                # generators that duplicate what is here.
    return targets


# The ordering seed, and it is a CONSTANT rather than a clock.
#
# A wall-clock seed would remove the alphabetical bias and replace it with a corpus whose every
# figure is unreproducible -- a trade this project has no interest in. A fixed default plus a
# recorded value gives both: the same inputs give the same order, and a reader who wants a different
# sample passes `--target-seed` and the results file says which one produced its rows.
DEFAULT_TARGET_SEED = 20260906

# The name recorded beside the seed, so a results file says which ORDERING produced its rows and not
# only which seed. Every recall and narrowing figure this corpus published before this ordering came
# from the alphabetical order this replaces; a seed alone would not tell those apart.
ORDERING = "reach-stratified/v1"


def reach_counts(map_dir):
    """class -> how many distinct tests the map records executing it.

    The same inversion `Audit` computes, and the reason this file needs
    it: a class reached by half the suite forces a full run when it is mutated, so a sample drawn
    from those classes makes recall look perfect and narrowing look terrible at the same time.

    EMPTY means "no map to read", exactly as `covered_classes` does, and callers must treat it as
    unknown rather than as "nothing is reached".
    """
    if not map_dir:
        return {}
    coverage = os.path.join(map_dir, "coverage.tsv")
    if not os.path.isfile(coverage):
        return {}
    counts = {}
    with open(coverage, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            fields = line.rstrip("\n").split("\t")
            # Field 2 is the classes, field 3 the test id. Short lines are SKIPPED rather than
            # guessed at -- reading field 2 of a three-field line is how the previous defect in this
            # file stayed plausible for as long as it did.
            if len(fields) < 4:
                continue
            for name in fields[2].split(","):
                if name:
                    counts[name] = counts.get(name, 0) + 1
    return counts


def reach_of(name, reach):
    """How many tests reach the class this source path declares, or 0 when nothing does.

    The same three name rules `is_covered` applies, and for the same reason: the compiler emits
    `FooKt` for a Kotlin file's top-level declarations and `Foo$Nested` for anything declared inside
    it, so matching the bare name alone reads every Kotlin file class as unreached.
    """
    if name is None:
        return 0
    direct = reach.get(name, 0) + reach.get(name + "Kt", 0)
    nested = sum(count for other, count in reach.items() if other.startswith(name + "$"))
    return direct + nested


def by_reach(targets, reach, seed=DEFAULT_TARGET_SEED):
    """Orders targets so a sample spans the suite's shape instead of its alphabet.

    **THE BIAS THIS REPLACES IS IN EVERY RECALL AND NARROWING FIGURE THIS CORPUS HAS PUBLISHED.**
    `mutation_targets` sorts paths, and sorted paths start with `Abstract*` -- base classes, which
    are hubs, and a mutation on a hub forces a full run. So recall came back perfect (a full run
    cannot miss anything) and narrowing came back terrible (nothing narrowed), systematically, on
    every project, in every sweep.

    Two properties, and it takes both:

    - **Spanning.** Targets are split into hub, mid and leaf by the map's own reach counts and taken
      round-robin, so a `--limit` of six draws some of each rather than six hubs. Classes the map
      reaches not at all go in a fourth group -- `by_coverage` buckets those separately anyway, and
      this must not disturb that.
    - **Reproducible from the results file alone.** The order inside a stratum is a seeded hash of
      the target, not `random.shuffle` and not a clock, so the same targets and the same seed give
      the same order on any machine and in any Python process. The seed and the ordering NAME are
      both written into the results file, because a seed alone cannot tell this ordering from the
      alphabetical one that produced every earlier figure.

    Returns the reordered list. Callers keep passing it to `by_coverage`, which buckets and weaves
    without disturbing relative order -- so this one sort reaches every stage below it.
    """
    if not reach:
        # No map: nothing to stratify by, and inventing strata from an unread map is exactly the
        # empty-is-not-unknown defect this file has already shipped once.
        return list(targets)

    scored = [(target, reach_of(qualified_name(target[0]), reach)) for target in targets]
    positive = sorted({score for _, score in scored if score > 0})
    if not positive:
        return list(targets)
    # Terciles of the reach values PRESENT, not of an absolute share. A suite whose busiest class is
    # reached by 3% of tests still has a top third, and that third is still where a mutation is most
    # likely to force a full run.
    low = positive[len(positive) // 3]
    high = positive[(2 * len(positive)) // 3]

    def stratum(score):
        if score <= 0:
            return 3
        return 0 if score >= high else (1 if score >= low else 2)

    def shuffled(target):
        # A hash of the seed and the target, so the order is a pure function of both. Python's own
        # hash() is salted per process and would make this irreproducible in the least visible way
        # available: the same command, twice, in two orders, with nothing saying so.
        key = f"{seed}:{target[0]}:{target[1]}:{target[2]}".encode("utf-8")
        return hashlib.sha256(key).hexdigest()

    buckets = [[], [], [], []]
    for target, score in sorted(scored, key=lambda pair: shuffled(pair[0])):
        buckets[stratum(score)].append(target)
    ordered = []
    position = 0
    while any(position < len(bucket) for bucket in buckets):
        for bucket in buckets:
            if position < len(bucket):
                ordered.append(bucket[position])
        position += 1
    return ordered


def interleave_shapes(targets):
    """Round-robins the targets by shape, so the limit samples every shape rather than the first file.

    Targets come out of mutation_targets in file order, with each file's shapes together, and the
    sweep takes the first N. On a project with more than a handful of source files that means every
    mutation in a run comes from the first file or two -- and a shape added at the end of the list
    is never reached at all. The `schema` shape, which is the only one that can catch a rule
    narrowing on the absence of coverage, was added and would have been sliced off on every real
    project while the demo fixture (four files) exercised it and reported success.

    A corpus whose newest shape is unreachable is the corpus equivalent of a guard that never fires.
    """
    by_kind = {}
    for target in targets:
        by_kind.setdefault(target[1], []).append(target)
    ordered = []
    # Sorted so the order is stable across runs: a resumed sweep must not reshuffle what is pending.
    queues = [by_kind[kind] for kind in sorted(by_kind)]
    position = 0
    while any(position < len(queue) for queue in queues):
        for queue in queues:
            if position < len(queue):
                ordered.append(queue[position])
        position += 1
    return ordered


def covered_classes(map_dir):
    """Every class the suite was recorded executing or loading, as fully qualified names.

    Read from the map the capture already wrote, so it costs nothing. Both files are needed: a class
    can be loaded without any test's coverage naming it, and either one means a mutation there has
    something that could notice.

    Returns an EMPTY set only when there is no map to read. Callers must treat that as "unknown",
    not as "nothing is covered" -- confusing the two is a defect this harness has now shipped
    twice.
    """
    if not map_dir:
        return set()
    names = set()
    coverage = os.path.join(map_dir, "coverage.tsv")
    if os.path.isfile(coverage):
        with open(coverage, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                fields = line.rstrip("\n").split("\t")
                # `outcome \t durationNanos \t classes \t testId`. The classes are field 2, and this
                # read `fields[-1]` -- the TEST ID -- since the day it was written, so it had never
                # returned a class name. Verified against a real rxjava map: 15,904 entries came
                # back and every one was shaped `[engine:junit-jupiter]/[class:...]/[method:...]`,
                # while `AtomicThrowable`, which that map mentions 3,296 times, read as uncovered.
                # Every mutation target on every project was therefore classified "uncovered", and
                # `by_coverage` ordering has been meaningless corpus-wide.
                #
                # Short lines are SKIPPED rather than guessed at. Reading field 2 of a three-field
                # line would collect whatever happened to be there, which is how this defect looked
                # plausible for as long as it did -- the unit fixture had three columns.
                if len(fields) < 4:
                    continue
                names.update(part for part in fields[2].split(",") if part)
    loaded = os.path.join(map_dir, "loaded.txt")
    if os.path.isfile(loaded):
        with open(loaded, encoding="utf-8", errors="replace") as handle:
            names.update(line.strip() for line in handle if line.strip())
    return names


def qualified_name(relative):
    """The class a source path most likely declares, or None when the path does not say.

    None is not "uncovered". A file whose name does not name its class -- the `aliased` shape, which
    exists precisely because it defeated an earlier version of the selector -- cannot be resolved
    this way, and guessing would push exactly the targets that once broke the tool to the back of
    every run.
    """
    # Leading slash added before matching, because on a single-module project the path starts at
    # `src/main/java/` with nothing in front of it. Without this every target on such a project
    # resolves to None, is classified "unknown", and the ordering silently does nothing -- which is
    # exactly what the demo fixture did on the first run of this code.
    normalised = "/" + relative.replace("\\", "/").lstrip("/")
    for marker in ("/src/main/java/", "/src/main/kotlin/", "/src/test/java/", "/src/test/kotlin/"):
        if marker in normalised:
            tail = normalised.split(marker, 1)[1]
            stem = tail.rsplit(".", 1)[0]
            return stem.replace("/", ".")
    return None


# Shapes that change what a test observes, and so can induce a failure for selection to miss.
# `constant` is here because a constant a test asserts on does fail it -- serialVersionUID does not,
# but that is a property of the constant chosen, not of the shape.
#
# `schema` is deliberately absent, but not because it never induces a failure -- on the demo fixture
# it induces one on Delta and one on Epsilon, because those tests introspect. That is the shape
# working as designed. It is absent because it induces one only where a test happens to introspect,
# which no project can be assumed to do: on kestra it broke nothing, three times. A shape that
# answers on some projects and not others cannot be counted on to make a run answerable.
#
# The recall-arm shapes are all answering: each changes a value or a body that a test which consumed
# it asserts on, which is the whole reason the arm exists. They are listed here rather than left out
# so the ordering budgets them like any other answering shape; they simply never appear unless the
# caller asked for that shape set.
ANSWERING_SHAPES = ("body", "aliased", "renamed", "constant",
                    "inline", "nested-constant", "second-type")


def weave(answering, other, ratio=2):
    """Interleaves two ordered lists, taking `ratio` from the first for each one from the second.

    Both properties have to hold at once, and each was learned the hard way.

    A run whose mutations cannot fail asks nothing: six of six kestra mutations were inert
    once, and four of six still were after target ordering fixed the classes, because the
    shapes drawn could not fail either.

    But a shape that a small --limit never reaches is a guard that never fires. `interleave_shapes`
    exists because `schema` -- the only shape that can catch a rule narrowing on absence of coverage
    -- was added at the end of the target list and sliced off on every real project while the demo
    fixture exercised it and reported success.

    So this does not promote one over the other. It budgets: at ratio 2, a --limit of 6 spends four
    mutations on questions that can be answered and two on the shape that checks the opposite rule.
    """
    result = []
    answering, other = list(answering), list(other)
    a = o = 0
    while a < len(answering) or o < len(other):
        for _ in range(ratio):
            if a < len(answering):
                result.append(answering[a])
                a += 1
        if o < len(other):
            result.append(other[o])
            o += 1
    return result


def by_shape(targets):
    """Orders one bucket so answerable shapes lead, without starving the inert-by-design ones."""
    answering = [t for t in targets if t[1] in ANSWERING_SHAPES]
    other = [t for t in targets if t[1] not in ANSWERING_SHAPES]
    return weave(interleave_shapes(answering), interleave_shapes(other))


def pending_targets(targets, findings):
    """The targets not already measured, matched by declaration rather than by file and shape.

    **The defect this replaces silently capped every project's evidence.** The filter keyed on
    `path::kind`, so once ONE body mutation in a file had been measured, every other body target in
    that file was excluded -- forever, in every later sweep. A resumed run could only move to new
    files or new shapes, never deepen a file, so a project's recall evidence stopped growing after
    its first pass and no amount of re-running would add to it. kestra drew from the same handful of
    files across three sweeps for this reason.

    Matched on the index now, which is what identifies the declaration. Rows written before the index
    was recorded fall back to the old behaviour for their own file and shape only: their index is
    genuinely unknown, and re-measuring a row already measured is a waste, while skipping one never
    measured is a hole in the evidence. Old rows are rare in practice -- results are discarded
    whenever the plugin commit changes, which is most days.
    """
    measured = set()
    legacy = set()
    for finding in findings:
        key = finding.get("target", "").rsplit("::", 1)[0]
        if "index" in finding:
            measured.add((key, finding["index"]))
        else:
            legacy.add(key)
    return [
        target for target in targets
        if (f"{target[0]}::{target[1]}", target[2]) not in measured
        and f"{target[0]}::{target[1]}" not in legacy
    ]


def is_covered(name, covered):
    """Whether the map records a class this source path could have produced.

    The path names `dev.demokt.Inlined`; the compiler emits `dev.demokt.InlinedKt` for the file's
    top-level declarations, and `dev.demokt.Inlined$Nested` for anything declared inside. Matching
    the bare name alone therefore read every Kotlin file class as UNCOVERED and sorted it last --
    which quietly starved inline targets of the mutation budget on exactly the projects where the
    inline defect lives. `TaskArtifacts.declaredBy` has applied the same three rules on the plugin
    side all along; this is the harness catching up to it.
    """
    return (name in covered
            or name + "Kt" in covered
            or any(other.startswith(name + "$") for other in covered))


def by_coverage(targets, covered):
    """Orders targets so the ones that can actually fail something come first.

    A mutation to a class no test loads cannot induce a failure, so the run asks nothing about
    recall. Six of six kestra mutations were inert for this reason once, four of them
    because the target was unreachable and two because the shape was inert on a covered class.

    Ordering, not filtering. An uncovered class is still worth mutating -- it is the shape the
    absence-of-coverage rule exists to handle, and dropping it would remove the corpus's only check
    on that rule. It simply must not consume a small --limit before anything answerable does.

    Returns (ordered, provenance): provenance maps each target to "covered", "unknown" or
    "uncovered", which is recorded per row so a finished sweep can say which kind it measured.
    """
    if not covered:
        return by_shape(targets), {target: "unknown" for target in targets}
    buckets = {"covered": [], "unknown": [], "uncovered": []}
    provenance = {}
    for target in targets:
        name = qualified_name(target[0])
        if name is None:
            where = "unknown"
        elif is_covered(name, covered):
            where = "covered"
        else:
            where = "uncovered"
        buckets[where].append(target)
        provenance[target] = where
    ordered = (by_shape(buckets["covered"])
               + by_shape(buckets["unknown"])
               + by_shape(buckets["uncovered"]))
    return ordered, provenance
