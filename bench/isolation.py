"""
The isolated re-run that decides whether a "missed" test really was one.

Split out of `run.py` when that file passed 1,100 lines, on a seam the review named. A miss is the
only finding this harness exists to produce, and this is the ONLY code allowed to discount one, so
it is worth having somewhere a reader can hold in their head whole.

Gradle, the results reader and the map snapshots are passed in rather than imported. `run.py` owns
all four -- they know about wrappers, JDKs and project files, which this module deliberately does
not -- and injecting them keeps the dependency one-way: no import cycle, and the tests hand it
fakes rather than reaching into another module to swap them.
"""

import os
import time

from process import STALLED

# What one isolated re-run established. Three values, not two, because the third is the one both
# callers have to think about: a build that did not build, was killed for going silent, or whose
# filter matched nothing has ANSWERED NOTHING, and that is different from an answer of "it failed".
#
# The two callers resolve UNKNOWN in opposite directions, and both are the conservative one:
# `order_dependent` below keeps the test a MISS, because a check that cannot answer must not clear a
# miss; an exposure check keeps the test FLAGGED AS EXPOSED, because a check that cannot answer must
# not clear an exposure. Getting either backwards produces a reassuring number.
UNKNOWN, PASSES_ALONE, FAILS_ALONE = "unknown", "passes-alone", "fails-alone"

# How many times a flagged test is re-run by itself before its answer is believed. Three, because
# two cannot distinguish "agreed" from "both happened to land the same way" any better than one
# does, and each of these is a single class run rather than a suite.
#
# Lives here rather than in the exposure check, where it was written first, because both callers
# repeat and a constant that means "how many isolated runs settle a question" with two definitions
# is the shape this repository has been bitten by more than once.
ISOLATED_REPEATS = 3


class XmlKeys:
    """Every comparison on the JUnit XML key, with no shared-key rule: an exposure check's, whose
    two arms are always read the same way. A sweep row passes its own `run.RowKeys` instead."""

    def shared(self, test):
        return False

    def alone(self, outcomes, test, where):
        return test, outcomes[0], outcomes[1]


XML_KEYS = XmlKeys()


def outcome_alone(checkout, project, module, cls, name, scratch, stem, *,
                  gradle, results_of, flags=(), task, keys, phase="isolated re-run"):
    """Run one test by itself against the tree as it stands, and report what happened.

    Extracted from `order_dependent` so the order-exposure detector can confirm a flip with the same
    primitive rather than growing a second, subtly different copy of it. The subtleties are the
    whole value: `since` is taken immediately before the run rather than 0, because with since=0
    every result file on disk counts as fresh and a leftover XML naming this class -- from an
    earlier mutation, or from another source set sharing the name -- answers a question this run
    never asked.

    THE EXIT CODE IS NOT SYMMETRIC, and this is the part worth reading twice. A Gradle test task
    exits non-zero precisely BECAUSE a test failed, so treating every non-zero exit as UNKNOWN made
    FAILS_ALONE unreachable through a real build -- the earlier version of this could only ever
    answer "passed alone" or "do not know", which was invisible while the only caller consumed
    PASSES_ALONE and nothing else.

    So the two directions are gated differently, deliberately and not for convenience:

    * PASSES_ALONE requires a CLEAN exit AND fresh results naming the test. It is the only answer
      that changes a caller's classification at all, so a build that failed for some unrelated
      reason must never produce it, however the XML reads. NOTE it does not discount anything in
      `order_dependent`: that caller LABELS the row and the miss is still counted and still fails
      `assert_recall.py`, which reads `missed` and never subtracts `order_dependent`.
    * FAILS_ALONE needs only fresh results naming the test as failed, because no SINGLE answer
      clears anything: it keeps the miss in `order_dependent` and, on its own, keeps the exposure
      flagged in an exposure check. Loosening a direction that can only ever be conservative costs
      nothing.

      One caveat: BOTH callers now call this
      ISOLATED_REPEATS times and reclassify a test whose own answers DISAGREE as nondeterministic,
      which drops it from the exposed count in one and from the missed count in the other. That is
      not this function loosening -- each answer is still conservative -- it is the caller treating
      "this test cannot repeat its own isolated result" as a different finding. A single UNKNOWN
      among the repeats still clears nothing in either caller; only two answers contradicting each
      other do.

      THE ASYMMETRY THIS REPLACED COST SOMETHING: kafka's first sweep published two
      missed failures that are almost certainly flaky TLS tests -- one of them "induced" by a
      mutation that only adds an unused method and cannot change behaviour, the other proved
      nondeterministic by a FORCED row of the same sweep where both arms ran identical tests and it
      failed in one. A single baseline run lets an intermittent test count as induced, and a single
      isolated re-run could not tell flaky from order-dependent.
    * STALLED stays UNKNOWN outright. A build killed for going silent may have been killed in the
      middle of the very test being asked about, so its results describe an interrupted run.

    `keys` says which key the run is read on (`run.RowKeys.alone`): the row's per-method key when
    this build's outcomes are complete, else the XML key -- and UNKNOWN when that XML key may stand
    for a sibling, since the class re-run then answers for whichever of them ran.
    """
    log = os.path.join(scratch, f"isolate-{stem}-{cls.rsplit('.', 1)[-1]}.log")
    started = time.time()
    code, _ = gradle(
        checkout, project,
        [f":{module}:{task}" if module else task, "--tests", cls, "--rerun-tasks"] + list(flags),
        log)
    if code == STALLED:
        return UNKNOWN
    outcomes = results_of(checkout, module, started, task, log)
    if outcomes[0] is None:
        return UNKNOWN
    answer = keys.alone(outcomes, (cls, name), phase)
    if answer is None:
        return UNKNOWN
    test, ran, failed = answer
    if test not in ran:
        return UNKNOWN
    if test in set(failed):
        return FAILS_ALONE
    if code == 0:
        return PASSES_ALONE
    # A NON-ZERO EXIT THAT THE RESULTS THEMSELVES EXPLAIN, added on kafka-core.
    #
    # The rule above refuses to read the XML after a failed build because the build may have failed
    # for a reason this function cannot see. That is right when nothing accounts for the exit code
    # -- and this asks whether something does. The filter runs the whole CLASS, so a sibling test
    # failing makes the task exit non-zero while saying nothing whatever about the test being
    # asked about.
    #
    # `kafka.network.SocketServerTest` fails 5 or 6 of its 61 tests on EVERY run of that checkout,
    # mutated or not. So PASSES_ALONE was unreachable for every test in it: `order_dependent` could
    # never clear one as order-dependent, the control arm could never confirm one passes with the
    # change reverted, and both answered UNKNOWN, which keeps a miss. A single flaky failure in
    # three isolated repeats then pinned `remoteCloseSendFailure` as a genuine selector miss, on
    # one observation against two that were merely unreadable.
    #
    # This LOOSENS the only code allowed to discount a miss, so it is deliberately narrow: the
    # failures have to be present in this run's own fresh results and have to belong to a DIFFERENT
    # test. An exit code with nothing in the results to account for it is still UNKNOWN, which is
    # the compile failure, the plugin error and the infrastructure fault the original rule was
    # written for. It is also not one-directional -- the same answer lets the control arm disqualify
    # an environmental failure -- so it is not a rule that can only ever improve the recall figure.
    if {other for other in failed if other != test}:
        return PASSES_ALONE
    return UNKNOWN


def order_dependent(checkout, project, module, missed, scratch, stem, map_dir=None, *,
                    gradle, results_of, backup_map, restore_map, flags=(), task, keys):
    """Which of these "missed" tests also fail with NOTHING selected -- i.e. not because of the code.

    A miss is the only finding this harness exists to produce, so it has to be right. Ground truth
    comes from a full run and attributes every failure in it to the mutation, which is sound only if
    a test's outcome depends on the code alone. On spring-beans it does not:
    ConstructorResolverAotTests::beanDefinitionWithClassArrayConstructorArgAndStringValueType failed
    the full run and PASSED, with the same mutation in place, when its class was run by itself -- 35
    tests, 0 failures. It fails only after certain other tests have shared a JVM with it.

    Reported as a miss, that is a false alarm about the one thing this project must not get wrong,
    and it would have been believed: the row also showed a test that failed the full run and passed
    the selected one, which is the same phenomenon seen from the other side.

    So each candidate is re-run ALONE against the same mutated tree, ISOLATED_REPEATS times. A test
    that still fails alone every time is a real miss -- the map lacked an edge the code has. One
    that passes alone every time was never evidence about selection.

    A test whose own isolated answers CONTRADICT EACH OTHER is neither. It is nondeterministic: it
    gave two different answers to the same question with the tree held still, so nothing it did in
    either arm is evidence about selection, and the ground truth that named it a failure is not
    trustworthy about it. Reported separately, counted as neither missed nor clean -- the standing
    this repository already gives armeria's identity-hash tests. Before this, one PASSES_ALONE was
    enough to label a flaky test order-dependent, and an order-dependent test is still COUNTED as
    missed, so kafka's two flaky TLS tests were published as missed failures.

    Returns (order_dependent, nondeterministic). On any doubt short of a contradiction -- the run
    did not build, it was killed for going silent, the filter matched nothing, the class could not
    be told from the method -- the test stays a miss. A check that cannot answer must not clear a
    miss, so a single UNKNOWN among the repeats keeps one; only two answers that disagree move a
    test out of the count.

    Bracketed by a map backup, like the ground-truth run and for the same reason. The init script
    is not passed here, but a checkout that applies the plugin in its own build file decodes on
    every run -- so this verification MUTATED the map it is verifying against, and the next row
    selected against a map one filtered run had rewritten. The ground-truth run has been bracketed
    since that was found there; this one was missed.

    A miss whose key may stand for a sibling (`keys.shared`) is not re-run at all: the class re-run
    answers for whichever of them ran, so a flaky sibling would read as this test contradicting
    itself and clear a real miss. It stays missed, unlabelled.
    """
    dependent, nondeterministic = set(), set()
    backup = backup_map(map_dir, scratch, f"map-before-isolation-{stem}") if map_dir else None
    try:
        for cls, name in missed:
            if keys.shared((cls, name)):
                continue
            alone = [
                outcome_alone(checkout, project, module, cls, name, scratch, f"{stem}-{attempt}",
                              gradle=gradle, results_of=results_of, flags=flags, task=task,
                              keys=keys)
                for attempt in range(ISOLATED_REPEATS)
            ]
            # UNKNOWN says nothing at all, so it never contributes to a disagreement -- but it also
            # never clears, which is why it is dropped here and tested for again below.
            answers = {a for a in alone if a != UNKNOWN}
            # A DISAGREEMENT IS NOT AUTOMATICALLY A COIN FLIP, and review caught the case where
            # treating it as one erases a real miss: FAILS, FAILS, PASSES disagrees, and the best
            # reading of it is that the test DOES fail by itself. Reclassifying that would take a
            # test out of the missed count on the strength of one run in three going the other way,
            # which is a recall figure improving for the wrong reason -- this project's own
            # signature for a silent skip.
            #
            # So a majority that failed alone keeps the miss. Only a disagreement the failures do
            # not win moves a test out of the count, which is still the kafka case the repeats were
            # added for: a flaky test that mostly passes by itself.
            if alone.count(FAILS_ALONE) > alone.count(PASSES_ALONE):
                continue
            if len(answers) > 1:
                nondeterministic.add((cls, name))
            elif answers == {PASSES_ALONE} and UNKNOWN not in alone:
                # The one branch that may clear to order-dependent: it ran, alone, against the same
                # tree, and passed EVERY time. An UNKNOWN anywhere in the repeats keeps the miss.
                dependent.add((cls, name))
    finally:
        if backup:
            restore_map(backup, map_dir)
    return dependent, nondeterministic


def fails_without_the_change(checkout, project, module, missed, scratch, stem, map_dir=None, *,
                             gradle, results_of, backup_map, restore_map, flags=(), task, keys):
    """Which of these "missed" tests fail by themselves even with the change REVERTED.

    THE CALLER MUST HAVE RESTORED THE TREE FIRST. This module is given its gradle, its reader and
    its map snapshots rather than reaching for them, and for the same reason it is not handed the
    authority to revert a mutation: `run.py` owns the tree, so `run.py` restores it and this asks
    the question.

    The hole this closes, measured on kafka-core. `order_dependent` above asks one
    question -- does this test pass when run alone? -- and reads "fails alone" as proof of a real
    miss. It cannot tell a test that is order-INdependent and genuinely broken by the change from
    one that fails alone because a port is held. Four tests in `kafka.network.SocketServerTest` were
    classified as missed failures induced by mutations to share-group code, which has no coverage
    edge to the network layer; that class fails 5 or 6 of its 61 tests on EVERY unmutated run, a
    different subset each time, and one of the four was reproduced failing with no mutation present.

    `already_failing` is supposed to catch this and cannot: it is established by full-suite baseline
    runs, so a test that fails only sometimes, or only under isolation, walks straight through it --
    even at two repeats, which is what that project was swept with.

    ONE failure here is enough to disqualify a test, and that direction is not a loosening. The
    whole premise of an induced failure is that the test passes without the change; a single run
    proving it fails without the change destroys that premise outright. There is no symmetric claim
    to make -- passing here proves nothing, because the test may still be intermittent -- so
    PASSES_ALONE and UNKNOWN both leave the miss exactly where it was.

    Returns the set that failed. Bracketed by a map backup like every other arm that runs the
    guest's tests: the plugin decodes on each run in a checkout that applies it in its own build
    file, so an unbracketed verification rewrites the map it is verifying against.

    NOT YET APPLIED TO THE REPLAY PATH, and that is a stated gap rather than an
    oversight: there the change is a real commit, so the control is the test run alone at its
    PARENT commit, which is a different manoeuvre from reverting a mutation in place. A rule applied
    at one call site and not the other is how this repository has found the same defect twice, so
    it is written down here rather than left to be rediscovered.

    A miss whose key may stand for a sibling (`keys.shared`) is not asked: a sibling failing with
    the change reverted under the same key says nothing about the test that failed.
    """
    unattributable = set()
    backup = backup_map(map_dir, scratch, f"map-before-control-{stem}") if map_dir else None
    try:
        for cls, name in missed:
            if keys.shared((cls, name)):
                continue
            for attempt in range(ISOLATED_REPEATS):
                if outcome_alone(checkout, project, module, cls, name, scratch,
                                 f"{stem}-control-{attempt}", gradle=gradle, results_of=results_of,
                                 flags=flags, task=task, keys=keys,
                                 phase="control arm") == FAILS_ALONE:
                    unattributable.add((cls, name))
                    # Proven. Further repeats cannot add to a claim that is already established,
                    # and each one is a real test task on the guest's build.
                    break
    finally:
        if backup:
            restore_map(backup, map_dir)
    return unattributable
