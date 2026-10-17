"""
Tests for the mutation generators.

The point of each assertion is not that the generator edits a file. It is that the edit has the
*shape* that defeated the selector: a constant whose dependents do not appear in the change set, a
move whose origin can go unmentioned, a file whose path names a class that does not exist.

Run with `python bench/test_mutations.py`, or under pytest.

The cases are named case_* rather than test_* on purpose. They report by RETURNING a count of failed
checks, and a function that returns instead of asserting is collected by pytest and passes however
many checks failed -- a test that cannot fail, which is the shape this whole project exists to catch.
The single test_* entry point below is the one pytest sees, and it asserts.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mutations  # noqa: E402
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from recall_check import mutate, targets_in  # noqa: E402  (the same grammar the sweep uses)


def check(name, condition, detail=""):
    if condition:
        print(f"  ok   {name}")
        return 0
    print(f"  FAIL {name} {detail}")
    return 1


def write(root, relative, text):
    path = os.path.join(root, relative)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)
    return path


def case_java_constant(root):
    path = write(root, "Limits.java",
                 "package p;\npublic interface Limits {\n    int MAX = 7;\n}\n")
    name = mutations.constant(path)
    text = open(path, encoding="utf-8").read()
    return (check("java constant is changed", "MAX = 8" in text, text) +
            check("java constant is named", name == "MAX", str(name)) +
            check("java constant is detected", mutations.has_constant(path)))


def case_kotlin_constant(root):
    path = write(root, "Timeouts.kt",
                 "package p\n\nobject Timeouts {\n    const val WAIT = 5000\n}\n")
    mutations.constant(path)
    text = open(path, encoding="utf-8").read()
    return check("kotlin const val is changed", "WAIT = 5001" in text, text)


def case_string_constant(root):
    path = write(root, "Names.java",
                 'package p;\npublic class Names {\n    public static final String A = "x";\n}\n')
    mutations.constant(path)
    text = open(path, encoding="utf-8").read()
    return check("string constant is changed", 'A = "yoriwake-mutated"' in text, text)


def case_aliased_detection(root):
    aliased = write(root, "Utils.kt", "package p\n\nclass Helper { fun n() = 1 }\n")
    named = write(root, "Thing.kt", "package p\n\nclass Thing { fun n() = 1 }\n")
    empty = write(root, "Nothing.kt", "package p\n\nfun free() = 1\n")
    return (check("a differently named type is aliased", mutations.is_aliased(aliased)) +
            check("a matching type is not aliased", not mutations.is_aliased(named)) +
            check("a file declaring no type is not aliased", not mutations.is_aliased(empty)) +
            check("declared types are found", mutations.declared_types(aliased) == ["Helper"],
                  str(mutations.declared_types(aliased))))


def case_rename(root):
    subprocess.run(["git", "init", "--quiet", root], check=True)
    path = write(root, "Moveable.kt", "package p\n\nclass Moveable { fun n() = 1 }\n")
    subprocess.run(["git", "-C", root, "add", "."], check=True)
    subprocess.run(["git", "-C", root, "-c", "user.email=t@e.com", "-c", "user.name=t",
                    "commit", "--quiet", "-m", "base"], check=True)

    label = mutations.renamed(path, root)
    moved = os.path.exists(os.path.join(root, "MoveableMoved.kt"))

    # The shape that matters: with rename detection on, git names only the destination.
    detected = subprocess.run(["git", "-C", root, "diff", "--name-only", "HEAD"],
                              capture_output=True, text=True).stdout.split()
    without = subprocess.run(["git", "-C", root, "diff", "--name-only", "--no-renames", "HEAD"],
                             capture_output=True, text=True).stdout.split()

    return (check("the file moved", moved) +
            check("the move is labelled", label == "Moveable->MoveableMoved", str(label)) +
            check("rename detection hides the origin", detected == ["MoveableMoved.kt"], str(detected)) +
            check("--no-renames names both sides", sorted(without) ==
                  ["Moveable.kt", "MoveableMoved.kt"], str(without)) +
            check("a Java file is refused", mutations.renamed(
                write(root, "J.java", "package p;\npublic class J {}\n"), root) is None))


def case_corpus_contains_all_four_shapes(root):
    """The corpus must actually produce every shape, not merely have a generator for each.

    The original corpus had one shape and reported zero missed failures while the selector was
    unsound in four ways it could not reach. A corpus is only evidence about the cases it contains,
    so this asserts the four are present rather than assuming the generators get used.
    """
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import run  # noqa: E402  (imported here so the module's Gradle plumbing is not needed earlier)

    subprocess.run(["git", "init", "--quiet", root], check=True)
    # Top-level, because the body generator only reaches top-level single-expression Boolean
    # functions — narrower even than "one shape", and worth knowing when reading its results.
    write(root, "src/main/kotlin/p/Plain.kt",
          "package p\n\nclass Plain\n\nfun check(): Boolean = true\n")
    write(root, "src/main/kotlin/p/Utils.kt",
          "package p\n\nclass Helper\n\nfun helping(): Boolean = true\n")
    write(root, "src/main/java/p/Limits.java",
          "package p;\npublic interface Limits {\n    int MAX = 7;\n}\n")
    subprocess.run(["git", "-C", root, "add", "."], check=True)
    subprocess.run(["git", "-C", root, "-c", "user.email=t@e.com", "-c", "user.name=t",
                    "commit", "--quiet", "-m", "base"], check=True)

    kinds = {kind for _, kind, _ in
             run.mutation_targets(root, {"sources": ["src/main/kotlin", "src/main/java"]})}

    return (check("body shape present", "body" in kinds, str(kinds)) +
            check("aliased shape present", "aliased" in kinds, str(kinds)) +
            check("constant shape present", "constant" in kinds, str(kinds)) +
            check("renamed shape present", "renamed" in kinds, str(kinds)))



def case_inline_fun_is_reachable(root):
    """An `inline fun` must be mutable, or the corpus cannot reach the hazard at all.

    An inline function's body is copied into every call site, so the code that runs lives in the
    CALLER's bytecode and the declaring class looks untouched by coverage. That is precisely why
    Recordability refuses every Kotlin class. With `inline` unmatched by the mutation grammar, no
    mutation could ever produce that shape, so a wrongly relaxed rule would have nothing in the
    corpus to catch it -- a guard with no way to fire.
    """
    path = write(root, "Inlined.kt",
                 "package p\n\ninline fun above(n: Int): Boolean = n > 0\n"
                 "fun below(n: Int): Boolean = n < 10\n")
    found = targets_in(path)
    name = mutate(path, 0)
    text = open(path, encoding="utf-8").read()
    return (check("inline and ordinary funs are both counted", found == 2, str(found)) +
            check("the inline fun is the one mutated", name == "above", str(name)) +
            check("its body is replaced", "inline fun above(n: Int): Boolean = false" in text, text) +
            check("the ordinary fun is untouched", "fun below(n: Int): Boolean = n < 10" in text,
                  text))


GUARD_CLAUSE = (
    "package p\n"
    "\n"
    "class GuardClause {\n"
    "    inline fun isGuardClause(depth: Int): Boolean {\n"
    "        return depth > 0\n"
    "    }\n"
    "\n"
    "    fun isSecond(depth: Int): Boolean {\n"
    "        return depth < 0\n"
    "    }\n"
    "}\n"
)


def case_block_bodied_inline_fun_is_mutated_by_index(root):
    """detekt's `inline fun isGuardClause(): Boolean {` shape, which had no test at all.

    `= expr` is only one of the two ways to write a Kotlin function, and the shape that motivated
    the entire inline investigation is the other one -- so until the block-bodied grammar existed,
    no mutation could reach it on any real Kotlin project.

    That grammar landed untested, directly beside a fix for a grammar bug of exactly this shape:
    one that silently mutated the SECOND declaration when asked for the first. So the INDEX is
    asserted here as well as the name, in both directions. A mutation applied to a declaration
    other than the one recorded makes every recall row in the results file a claim about something
    that was never measured -- which is worse than no row at all.
    """
    path = write(root, "GuardClause.kt", GUARD_CLAUSE)
    found = targets_in(path)
    first = mutate(path, 0)
    text = open(path, encoding="utf-8").read()
    failures = (
        check("both block-bodied Boolean funs are counted", found == 2, str(found)) +
        check("index 0 names the inline fun", first == "isGuardClause", str(first)) +
        check("the inline fun's body is short-circuited",
              "isGuardClause(depth: Int): Boolean {\n        if (true) {" in text, text) +
        check("the second fun is untouched by index 0",
              "isSecond(depth: Int): Boolean {\n        return depth < 0" in text, text)
    )

    # And the other direction, which is the half the fixed grammar bug got wrong: asked for the
    # second declaration it must not reach for the first.
    other = write(root, "GuardClauseTwo.kt", GUARD_CLAUSE)
    second = mutate(other, 1)
    text = open(other, encoding="utf-8").read()
    return failures + (
        check("index 1 names the second fun", second == "isSecond", str(second)) +
        check("index 1 mutates the second fun",
              "isSecond(depth: Int): Boolean {\n        if (true) {" in text, text) +
        check("index 1 leaves the inline fun alone",
              "isGuardClause(depth: Int): Boolean {\n        return depth > 0" in text, text)
    )


def case_numeric_literals_in_every_notation_a_guest_actually_writes(root):
    """okhttp, and it cost that project its whole sweep.

    `const val TAG_CLASS_UNIVERSAL = 0b0000_0000`: the value pattern was `-?\\d+`, so it matched the
    leading `0`, the replacement left `b0000_0000` dangling, and :okhttp-tls:compileKotlin failed
    with "Unresolved reference". `assert_recall.py` exits 1 on any build-failed row.
    """
    failed = 0
    # A LITERAL TOO BIG TO BE AN INT is why the suffix is kept. mockito and rxjava each declare
    # five `private static final long serialVersionUID = ...L` constants; bumping one to a bare
    # decimal gives "error: integer number too large" and cost them five rows apiece.
    for lit, want in (("0b0000_0000", "0b00000001"), ("0x1F", "0x20"),
                      # A HEX LITERAL KEEPS ITS BASE AND WIDTH. Java lets hex fill the full
                      # unsigned width and wrap into the signed range; decimal may not. rxjava's
                      # `int INT_PHI = 0x9E3779B9` is negative as an int, and rendering the bump
                      # as decimal gave "error: integer number too large".
                      ("0x9E3779B9", "0x9E3779BA"), ("0xFFFFFFFF", "0x00000000"),
                      ("0x8000000000000000L", "0x8000000000000001L"),
                      # At the top of the decimal range the mutation steps DOWN: 2147483648 is not
                      # a legal int, and nothing here requires the change to be an increase.
                      ("2147483647", "2147483646"), ("1_000_000", "1000001"),
                      ("100L", "101L"), ("7", "8"), ("-3", "-2"), ("5f", "6f"), ("9d", "10d"),
                      ("-1172445254382395149L", "-1172445254382395148L"), ("-1L", "0L")):
        got = mutations._bumped(lit)
        failed += check(f"{lit} bumps to {want}", got == want, f"got {got}")

    kt = write(root, "DerHeader.kt",
               "class DerHeader {\n"
               "    const val TAG_CLASS_UNIVERSAL = 0b0000_0000\n"
               "    const val PLAIN = 7\n"
               "}\n")
    named = mutations.constant(kt)
    text = open(kt, encoding="utf-8").read()
    line = [l for l in text.splitlines() if "TAG_CLASS_UNIVERSAL" in l][0]
    failed += (
        check("the binary literal is the one named", named == "TAG_CLASS_UNIVERSAL", str(named)) +
        # The WHOLE literal is replaced, so no fragment of the old notation is left to dangle.
        check("no fragment of the binary literal survives", "b0000_0000" not in line, line) +
        check("and the value actually moved", "= 0b00000001" in line, line) +
        check("the untouched constant is untouched", "PLAIN = 7" in text, text)
    )

    java = write(root, "Mask.java",
                 "package p;\nclass Mask {\n    private static final int MASK = 0xFF_00;\n}\n")
    mutations.constant(java)
    jline = [l for l in open(java, encoding="utf-8").read().splitlines() if "MASK" in l][0]
    return failed + (
        # The separator goes, the BASE AND WIDTH stay: a hex literal may fill the full
        # unsigned width, and re-rendering it as decimal is what broke rxjava.
        check("a hex literal with separators is replaced whole", "_00" not in jline, jline) +
        check("and bumps from its own base, not from its first digit", "= 0xFF01;" in jline, jline)
    )


def case_a_string_constant_containing_an_escaped_quote_still_compiles(root):
    """apache-groovy, and it cost that project its whole sweep.

    `JsonLexer.SIMPLE_ESCAPES` is `"\\"\\\\/bfnrt"`. The value pattern was `"[^"]*"`, which cannot
    see an escaped quote, so it matched a truncated span; the replacement left `\\\\/bfnrt";`
    dangling and `:groovy-json:compileJava` failed with "unclosed string literal". `assert_recall.py`
    exits 1 on any build-failed row, so one constant carrying a `\\"` lost every row in the file.
    """
    path = write(root, "JsonLexer.java",
                 'package p;\n'
                 'class JsonLexer {\n'
                 '    private static final String SIMPLE_ESCAPES = "\\"\\\\/bfnrt";\n'
                 '    private static final String PLAIN = "fixed";\n'
                 '}\n')
    named = mutations.constant(path)
    text = open(path, encoding="utf-8").read()
    line = [l for l in text.splitlines() if "SIMPLE_ESCAPES" in l][0]
    return (
        check("the escaped-quote constant is the one named", named == "SIMPLE_ESCAPES", str(named)) +
        # The whole literal is replaced, so nothing of the original is left dangling after it.
        check("the mutated line is a complete assignment",
              line.count('"') == 2 and line.rstrip().endswith('";'), line) +
        check("no fragment of the original literal survives", "/bfnrt" not in line, line) +
        check("the untouched constant is untouched", 'PLAIN = "fixed"' in text, text)
    )


def case_the_injected_statement_carries_braces(root):
    """A guest's own compiler settings are not a property of the guest.

    opentelemetry-java runs Error Prone with -Werror, and its MissingBraces check rejects a braceless
    `if` body. THREE OF THAT PROJECT'S EIGHT ROWS were filed `build-failed` for this and nothing
    else, and `assert_recall.py` exits 1 on any of them -- so a whole project's recall evidence was
    lost to a pair of braces. Both languages are asserted: Kotlin is not affected by that check, and
    the two injections must not drift apart.
    """
    java = write(root, "Sized.java",
                 "package p;\n"
                 "public class Sized {\n"
                 "    public int size(int n) {\n"
                 "        return n + 1;\n"
                 "    }\n"
                 "}\n")
    named = mutate(java, 0)
    text = open(java, encoding="utf-8").read()
    failures = (
        check("the Java injection is a formatted block",
              "    if (true) {\n            return -1;\n        }" in text, text) +
        check("and no braceless form survives anywhere",
              "if (true) return" not in text, text) +
        check("nor a one-line brace, which kafka's Checkstyle LeftCurly rejects",
              "if (true) { return" not in text, text) +
        check("the method is still named", named == "size", str(named)) +
        # The mutation must still BREAK what it broke before. Braces are a syntax change, and a
        # syntax change that quietly stopped the sentinel being returned would turn every row in
        # the corpus into a question nobody asked.
        check("the sentinel is still what the method returns",
              text.index("return -1;") < text.index("return n + 1;"), text) +
        check("the original body is left intact", "return n + 1;" in text, text)
    )

    kotlin = write(root, "Guard.kt",
                   "class Guard {\n"
                   "    fun isDeep(depth: Int): Boolean {\n"
                   "        return depth > 0\n"
                   "    }\n"
                   "}\n")
    mutate(kotlin, 0)
    text = open(kotlin, encoding="utf-8").read()
    return failures + (
        check("the Kotlin injection is a formatted block too",
              "    if (true) {\n            return false\n        }" in text, text) +
        check("and its original body is left intact", "return depth > 0" in text, text)
    )


def case_the_recall_arm_shapes_are_never_in_the_default_grammar(root):
    """The default population must not grow, or figures taken before and after are incomparable.

    Every corpus savings and recall figure this repository has published was drawn from `default`.
    A shape added there silently changes what every future sweep samples -- that bias has
    already been seen running the other way, at 5 of 16 kafka targets changing a constant
    against a real history that does so far less often.
    """
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import target_ordering

    subprocess.run(["git", "init", "--quiet", root], check=True)
    write(root, "src/main/kotlin/p/Inlined.kt",
          "package p\n\ninline fun aboveZero(n: Int): Boolean = n > 0\n")
    write(root, "src/main/kotlin/p/Holder.kt",
          "package p\n\nclass Holder {\n    companion object {\n"
          "        const val INNER: Int = 5\n    }\n}\n")
    write(root, "src/main/kotlin/p/Two.kt",
          "package p\n\nclass First\n\nclass Second {\n    companion object {\n"
          "        const val OTHER: Int = 7\n    }\n}\n")
    sources = {"sources": ["src/main/kotlin"]}

    default = {kind for _, kind, _ in target_ordering.mutation_targets(root, sources)}
    arm = {kind for _, kind, _ in
           target_ordering.mutation_targets(root, sources, target_ordering.RECALL_ARM_SHAPES)}

    failures = (
        check("the default grammar carries no inline shape", "inline" not in default, str(default)) +
        check("the default grammar carries no nested-constant shape",
              "nested-constant" not in default, str(default)) +
        check("the default grammar carries no second-type shape",
              "second-type" not in default, str(default)) +
        check("the recall arm reaches an inline body", "inline" in arm, str(arm)) +
        check("the recall arm reaches a companion constant", "nested-constant" in arm, str(arm)) +
        check("the recall arm reaches a second top-level type", "second-type" in arm, str(arm)) +
        # A superset, not a replacement: the arm still needs the shapes that break things.
        check("the recall arm keeps every default shape", default <= arm,
              f"{sorted(default - arm)} dropped")
    )
    try:
        target_ordering.mutation_targets(root, sources, "made-up")
        failures += check("an unknown shape set is refused", False, "it was accepted")
    except ValueError:
        pass
    return failures


def case_each_recall_arm_shape_changes_what_it_names(root):
    """A generator that silently does nothing is reported as a shape that could not fail.

    Every mutation here must produce a tree that still compiles and a value that moved -- the
    harness cannot tell "this shape asked nothing" from "this generator is broken", and the first
    reads as a clean result.
    """
    inlined = write(root, "Inlined.kt",
                    "package p\n\ninline fun aboveZero(n: Int): Boolean = n > 0\n")
    holder = write(root, "Holder.kt",
                   "package p\n\nclass Holder {\n    companion object {\n"
                   "        const val INNER: Int = 5\n    }\n}\n")
    two = write(root, "Two.kt",
                "package p\n\nclass First\n\nclass Second {\n    companion object {\n"
                "        const val OTHER: Int = 7\n    }\n}\n")

    inline_name = mutations.inline_body(inlined)
    nested_name = mutations.nested_constant(holder)
    second_name = mutations.second_type(two)

    return (
        check("the inline mutation names the function", inline_name == "aboveZero", str(inline_name)) +
        check("the inline body inverted rather than vanished",
              "= n <= 0" in open(inlined, encoding="utf-8").read(),
              open(inlined, encoding="utf-8").read()) +
        check("the companion constant names the value", nested_name == "INNER", str(nested_name)) +
        check("the companion constant moved", "INNER: Int = 6" in open(holder, encoding="utf-8").read(),
              open(holder, encoding="utf-8").read()) +
        check("the second-type mutation names the second type", second_name == "Second",
              str(second_name)) +
        check("the second type's constant moved, and the first type is untouched",
              "OTHER: Int = 8" in open(two, encoding="utf-8").read(),
              open(two, encoding="utf-8").read()) +
        # A file with one type has no second type, and saying "nothing to do" is not the same as
        # mutating the first one.
        check("a single-type file yields no second-type mutation",
              mutations.second_type(holder) is None, "it mutated something") +
        check("a Java file yields no inline mutation",
              mutations.inline_body(write(root, "J.java", "package p;\npublic class J {}\n")) is None,
              "it mutated something") +
        # A target the predicate registers and the generator declines is a row that asked nothing,
        # which reads exactly like a shape that could not fail.
        check("an inline body nothing can flip is not registered as a target",
              not mutations.has_inline(write(
                  root, "Unflippable.kt",
                  "package p\n\ninline fun name(): String = someHelper(1, 2)\n")),
              "has_inline registered a target inline_body cannot mutate") +
        # A NESTED type is not a second TOP-LEVEL type -- the path prefix matches Foo$Nested, so
        # that case belongs to the nested-constant shape.
        check("a file with one top-level type and a nested one has no second type",
              not mutations.has_second_type(write(
                  root, "OnlyNested.kt",
                  "package p\n\nclass Outer {\n    class Inner {\n"
                  "        companion object {\n            const val N: Int = 3\n        }\n"
                  "    }\n}\n")),
              "a nested class was counted as a second top-level type")
    )


def case_the_recall_arm_shapes_bump_every_notation_and_only_nested_java_members(root):
    """Two ways the recall arm's constant shapes drew rows that meant something else.

    `nested_constant` and `second_type` bumped with `int(old) + 1`, so the first `1L` or `0x10`
    they drew raised and ended the sweep -- and every indented Java `serialVersionUID` qualifies.
    And in Java, indentation alone matched members of the file's own top-level class, which is the
    plain `constant` shape and never the `Foo$Nested` blind spot the nested shape is named for.
    """
    java = write(root, "B.java",
                 "public class B {\n    public static final int LIMIT = 7;\n"
                 "    static class N {\n        private static final long serialVersionUID = 1L;\n"
                 "    }\n}\n")
    top_only = write(root, "C.java",
                     "public class C {\n    // {\n    String s = \"{{\";\n"
                     "    public static final int LIMIT = 7;\n}\n")
    kotlin = write(root, "K.kt",
                   "class K {\n    companion object {\n        const val X = 0x10\n    }\n}\n")
    two = write(root, "Two.kt",
                "package p\n\nclass First\n\nclass Second {\n    companion object {\n"
                "        const val OTHER: Long = 7L\n    }\n}\n")
    java_name = mutations.nested_constant(java)
    java_text = open(java, encoding="utf-8").read()
    kotlin_name = mutations.nested_constant(kotlin)
    second_name = mutations.second_type(two)
    return (
        check("a Java nested constant is the nested type's member", java_name == "serialVersionUID",
              str(java_name)) +
        check("a suffixed literal is bumped with its suffix", "serialVersionUID = 2L;" in java_text,
              java_text) +
        check("the top-level member is untouched", "LIMIT = 7;" in java_text, java_text) +
        check("a Java file with only top-level members has no nested constant, braces in comments "
              "and strings notwithstanding", not mutations.has_nested_constant(top_only),
              "a top-level member was registered") +
        check("a hex companion constant is bumped in its own base", kotlin_name == "X"
              and "const val X = 0x11" in open(kotlin, encoding="utf-8").read(),
              open(kotlin, encoding="utf-8").read()) +
        check("a suffixed second-type constant is bumped", second_name == "Second"
              and "OTHER: Long = 8L" in open(two, encoding="utf-8").read(),
              open(two, encoding="utf-8").read()) +
        check("a negative hex literal keeps one sign and its radix",
              mutations._bumped("-0x1F") == "-0x20", mutations._bumped("-0x1F"))
    )


# ---------------------------------------------------------------------------------- pool/v1

def _pool():
    import pool_mutations  # noqa: PLC0415
    return pool_mutations


def _read(path):
    return open(path, encoding="utf-8").read()


def case_void_return_inserts_one_return_at_entry(root):
    pm = _pool()
    java = write(root, "Ledger.java",
                 "package p;\n"
                 "public class Ledger {\n"
                 "    private int n;\n"
                 "\n"
                 "    public final synchronized void add(int by) {\n"
                 "        n += by;\n"
                 "    }\n"
                 "}\n")
    name = pm.void_return(java)
    text = _read(java)
    kotlin = write(root, "Ledger.kt",
                   "class Ledger {\n"
                   "    private var n = 0\n"
                   "\n"
                   "    fun log() = println(n)\n"
                   "\n"
                   "    fun add(by: Int) {\n"
                   "        n += by\n"
                   "    }\n"
                   "}\n")
    counted = pm.void_returns_in(kotlin)
    kotlin_name = pm.void_return(kotlin)
    kotlin_text = _read(kotlin)
    return (
        check("a final synchronized void method is drawn", name == "add", str(name)) +
        check("one return at its entry, braced on its own lines",
              "void add(int by) {\n        if (true) {\n            return;\n        }\n"
              "        n += by;" in text, text) +
        check("exactly one return is added", text.count("return;") == 1, text) +
        check("an expression-bodied Unit function is skipped, the block-bodied one counted",
              counted == 1, str(counted)) +
        check("the Kotlin Unit function is the one named", kotlin_name == "add", str(kotlin_name)) +
        check("one return at the Kotlin function's entry",
              "fun add(by: Int) {\n        if (true) {\n            return\n        }\n"
              "        n += by" in kotlin_text, kotlin_text) +
        check("the expression-bodied function is untouched", "fun log() = println(n)\n" in kotlin_text,
              kotlin_text)
    )


def case_throw_entry_throws_at_entry_whatever_the_return_type(root):
    pm = _pool()
    java = write(root, "Boxes.java",
                 "package p;\n"
                 "import java.util.List;\n"
                 "public class Boxes {\n"
                 "    public <T extends Comparable<T>> List<T> wrap(T item) {\n"
                 "        return List.of(item);\n"
                 "    }\n"
                 "}\n")
    name = pm.throw_entry(java)
    text = _read(java)
    kotlin = write(root, "Names.kt",
                   "class Names {\n"
                   "    fun names(): List<String> = listOf(\"a\", \"b\")\n"
                   "}\n")
    kotlin_name = pm.throw_entry(kotlin)
    kotlin_text = _read(kotlin)
    return (
        check("a generic Java method is drawn", name == "wrap", str(name)) +
        check("it throws at entry, braced on its own lines",
              "List<T> wrap(T item) {\n        if (true) {\n"
              "            throw new IllegalStateException(\"yoriwake-mutated\");\n        }\n"
              "        return List.of(item);" in text, text) +
        check("an expression-bodied Kotlin function is drawn", kotlin_name == "names",
              str(kotlin_name)) +
        check("it throws at entry and keeps its expression as the other branch",
              "fun names(): List<String> = if (true) {\n"
              "        throw IllegalStateException(\"yoriwake-mutated\")\n"
              "    } else {\n        listOf(\"a\", \"b\")\n    }\n" in kotlin_text, kotlin_text)
    )


def case_constructors_initialisers_and_bodiless_methods_yield_no_target(root):
    pm = _pool()
    java = write(root, "Shapes.java",
                 "package p;\n"
                 "public abstract class Shapes {\n"
                 "    private int n;\n"
                 "\n"
                 "    public Shapes(int n) {\n"
                 "        this.n = n;\n"
                 "    }\n"
                 "\n"
                 "    static {\n"
                 "        System.out.println();\n"
                 "    }\n"
                 "\n"
                 "    {\n"
                 "        n = 1;\n"
                 "    }\n"
                 "\n"
                 "    public abstract void draw();\n"
                 "\n"
                 "    interface Area {\n"
                 "        double area();\n"
                 "    }\n"
                 "}\n")
    kotlin = write(root, "Shapes.kt",
                   "abstract class Shapes(private val n: Int) {\n"
                   "    constructor() : this(0) {\n"
                   "        println()\n"
                   "    }\n"
                   "\n"
                   "    init {\n"
                   "        println(n)\n"
                   "    }\n"
                   "\n"
                   "    abstract fun draw()\n"
                   "}\n"
                   "\n"
                   "interface Area {\n"
                   "    fun area(): Double\n"
                   "}\n")
    return sum(
        check(f"{os.path.basename(path)} yields no {what} target", count(path) == 0,
              str(count(path)))
        for path in (java, kotlin)
        for what, count in (("void-return", pm.void_returns_in), ("throw-entry", pm.entries_in),
                            ("member-delete", pm.overrides_in)))


def case_records_comments_and_strings_yield_no_target(root):
    """A record's header is not a method, and a declaration in a comment, a text block or a raw
    string is not code: an edit to the first never compiles, and one to the others changes
    nothing while passing the gate as a row of its family."""
    pm = _pool()
    java = write(root, "Outer.java",
                 "package p;\n"
                 "public class Outer {\n"
                 "    public record Point(int a, int b) {\n"
                 "    }\n"
                 "\n"
                 "    /*\n"
                 "    public void old() {\n"
                 "    }\n"
                 "    */\n"
                 "\n"
                 "    private static final String TEMPLATE = \"\"\"\n"
                 "        public void inText() {\n"
                 "        }\n"
                 "        \"\"\";\n"
                 "\n"
                 "    public void record(int n) {\n"
                 "        System.out.println(n);\n"
                 "    }\n"
                 "}\n")
    kotlin = write(root, "Gen.kt",
                   "class Gen {\n"
                   "    val source = \"\"\"\n"
                   "    fun gen() {\n"
                   "    }\n"
                   "    \"\"\"\n"
                   "\n"
                   "    fun real() {\n"
                   "        println(source)\n"
                   "    }\n"
                   "}\n")
    java_entries = pm.entries_in(java)
    java_name = pm.throw_entry(java)
    kotlin_name = pm.void_return(kotlin)
    return (
        check("only the method named `record` is a Java target", java_entries == 1
              and java_name == "record", f"{java_entries} entries, {java_name}: {_read(java)}") +
        check("the Kotlin function in a raw string is not drawn",
              kotlin_name == "real" and "fun gen() {\n    }" in _read(kotlin), _read(kotlin))
    )


def case_override_add_adds_one_override_to_a_class_that_lacks_it(root):
    pm = _pool()
    java = write(root, "Plain.java",
                 "package p;\n"
                 "public class Plain {\n"
                 "    private int n;\n"
                 "}\n")
    name = pm.override_add(java)
    text = _read(java)
    kotlin = write(root, "Plain.kt",
                   "class Plain(\n"
                   "    private val n: Int,\n"
                   ") : Any() {\n"
                   "    fun n(): Int = n\n"
                   "}\n")
    kotlin_name = pm.override_add(kotlin)
    kotlin_text = _read(kotlin)
    complete = write(root, "Complete.java",
                     "package p;\n"
                     "public class Complete {\n"
                     "    @Override\n    public String toString() {\n        return \"c\";\n    }\n"
                     "\n"
                     "    @Override\n    public int hashCode() {\n        return 1;\n    }\n"
                     "\n"
                     "    @Override\n    public boolean equals(Object other) {\n"
                     "        return other == this;\n    }\n"
                     "}\n")
    annotated = write(root, "Annotated.java",
                      "package p;\n"
                      "public class Annotated {\n"
                      "    @Override\n    public String toString() {\n"
                      "        return \"a\";\n    }\n"
                      "\n"
                      "    @Override\n    public int hashCode() {\n        return 1;\n    }\n"
                      "\n"
                      "    @Override\n    public boolean equals(@Nullable Object other) {\n"
                      "        return other == this;\n    }\n"
                      "}\n")
    data = write(root, "Point.kt", "data class Point(val x: Int) {\n    fun twice() = x * 2\n}\n")
    # An equals added to a class with no hashCode is an Error Prone error, so hashCode comes first.
    only_to_string = write(root, "Named.java",
                           "package p;\n"
                           "public class Named {\n"
                           "    @Override\n    public String toString() {\n        return \"n\";\n    }\n"
                           "}\n")
    return (
        check("a Java class without toString gains one", name == "toString", str(name)) +
        check("the added toString returns a fixed value",
              "    @Override\n    public String toString() {\n"
              "        return \"yoriwake-mutated\";\n    }\n" in text, text) +
        check("exactly one override is added", text.count("@Override") == 1, text) +
        check("a Kotlin class whose header runs over lines gains one toString",
              kotlin_name == "toString" and kotlin_text.count("override fun toString") == 1
              and ") : Any() {\n    override fun toString(): String = \"yoriwake-mutated\"\n\n"
                  "    fun n()" in kotlin_text, kotlin_text) +
        check("a class that overrides all three yields no target", not pm.can_override_add(complete),
              "it was drawn") +
        check("an equals whose parameter is annotated counts as declared",
              not pm.can_override_add(annotated), "a second equals was drawn") +
        check("a data class, whose overrides the compiler writes, yields no target",
              not pm.can_override_add(data), "it was drawn") +
        check("hashCode is added before equals", pm.override_add(only_to_string) == "hashCode",
              _read(only_to_string))
    )


def case_member_delete_removes_exactly_one_override(root):
    pm = _pool()
    java = write(root, "Labelled.java",
                 "package p;\n"
                 "public class Labelled {\n"
                 "    public int size() {\n        return 1;\n    }\n"
                 "\n"
                 "    /** The label. */\n"
                 "    @Override\n    public String toString() {\n        return \"l\";\n    }\n"
                 "\n"
                 "    @Override\n    public int hashCode() {\n        return 1;\n    }\n"
                 "}\n")
    counted = pm.overrides_in(java)
    name = pm.member_delete(java, 0)
    text = _read(java)
    kotlin = write(root, "Labelled.kt",
                   "class Labelled {\n"
                   "    fun size(): Int = 1\n"
                   "\n"
                   "    override fun toString(): String = \"l\"\n"
                   "}\n")
    kotlin_name = pm.member_delete(kotlin, 0)
    kotlin_text = _read(kotlin)
    plain = write(root, "Plain.kt", "class Plain {\n    fun size(): Int = 1\n}\n")
    trailing = write(root, "Trailing.java",
                     "package p;\n"
                     "public class Trailing {\n"
                     "    private int x = 1; /* a counter */\n"
                     "    @Override\n    public String toString() {\n        return \"t\";\n    }\n"
                     "}\n")
    pm.member_delete(trailing, 0)
    trailing_text = _read(trailing)
    return (
        check("a code line ending in a block comment is not taken as the override's comment",
              trailing_text == "package p;\npublic class Trailing {\n"
                               "    private int x = 1; /* a counter */\n}\n", trailing_text) +
        check("both overrides are counted, the plain method is not", counted == 2, str(counted)) +
        check("the first override is the one deleted", name == "toString", str(name)) +
        check("it is gone whole, with its Javadoc",
              "toString" not in text and "The label" not in text, text) +
        check("the other override and the plain method stay",
              "public int hashCode()" in text and "public int size()" in text, text) +
        check("one blank line is left between the members that remain",
              "    }\n\n    @Override\n    public int hashCode()" in text, text) +
        check("a Kotlin expression-bodied override is deleted",
              kotlin_name == "toString" and "toString" not in kotlin_text
              and kotlin_text == "class Labelled {\n    fun size(): Int = 1\n}\n", kotlin_text) +
        check("a file without an override yields no target", pm.overrides_in(plain) == 0,
              str(pm.overrides_in(plain)))
    )


def case_the_pool_edits_carry_braces_and_no_typed_literal(root):
    """The two defect classes the default generators paid for, asked of the new ones.

    Braces: a braceless `if` cost opentelemetry-java three rows to Error Prone's MissingBraces, and
    one-line braces cost kafka eight to Checkstyle's LeftCurly. A sentinel literal that is valid at
    one width only cost mockito and rxjava five rows apiece. And an edit after a `{` that shares its
    line with code would leave a statement where the formatter forbids one, so such a declaration is
    not drawn at all.
    """
    pm = _pool()
    java = write(root, "Sizes.java",
                 "package p;\n"
                 "public class Sizes {\n"
                 "    public long size() {\n        return 1L;\n    }\n"
                 "\n"
                 "    public void reset() { size(); }\n"
                 "}\n")
    kotlin = write(root, "Sizes.kt",
                   "class Sizes {\n"
                   "    fun size(): Long {\n        return 1L\n    }\n"
                   "\n"
                   "    fun reset() { size() }\n"
                   "\n"
                   "    fun chained(): List<Int> = listOf(1)\n"
                   "        .map { it }\n"
                   "}\n")
    failures = (
        check("a Java method whose `{` shares its line with code is not drawn",
              pm.entries_in(java) == 1 and pm.void_returns_in(java) == 0,
              f"{pm.entries_in(java)} entries, {pm.void_returns_in(java)} void") +
        check("nor a Kotlin one, nor an expression that runs over lines",
              pm.entries_in(kotlin) == 1 and pm.void_returns_in(kotlin) == 0,
              f"{pm.entries_in(kotlin)} entries, {pm.void_returns_in(kotlin)} void")
    )
    pm.throw_entry(java)
    pm.throw_entry(kotlin)
    for path in (java, kotlin):
        text = _read(path)
        failures += (
            check(f"{os.path.basename(path)}: no braceless if", "if (true) throw" not in text
                  and "if (true) return" not in text, text) +
            check(f"{os.path.basename(path)}: no one-line braces", "if (true) { " not in text, text) +
            check(f"{os.path.basename(path)}: the long method throws rather than returning a literal",
                  text.count("1L") == 1 and "IllegalStateException" in text, text)
        )
    return failures


def case_the_default_set_is_unchanged_by_the_pool(root):
    """The default set's members, order and targets on the demo fixtures, as they were before
    `pool/v1` existed. The fixtures gained a void method, overrides and a non-Boolean Kotlin
    function for the pool; none of them is a default target."""
    import target_ordering  # noqa: PLC0415
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import run  # noqa: PLC0415
    pm = _pool()
    samples = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "samples")
    demo = [(f"src/main/java/dev/demo/{name}.java", kind, index)
            for name in ("Alpha", "Beta", "Delta", "Epsilon", "Gamma")
            for kind, index in (("body", 0), ("body", 1), ("schema", 0))]
    kotlin = [(f"src/main/kotlin/dev/demokt/{name}.kt", kind, index) for name, kind, index in (
        ("Alpha", "body", 0), ("Alpha", "renamed", 0), ("Beta", "body", 0), ("Beta", "renamed", 0),
        ("Constants", "body", 0), ("Constants", "constant", 0), ("Constants", "renamed", 0),
        ("Inlined", "body", 0), ("Inlined", "body", 1), ("Inlined", "renamed", 0))]
    failures = (
        check("the shape sets the grammar defines are default and recall-arm, in that order",
              target_ordering.SHAPE_SETS == ("default", "recall-arm"),
              str(target_ordering.SHAPE_SETS)) +
        check("the sweep offers pool/v1 after them",
              run.SHAPE_SETS[:2] == ("default", "recall-arm") and "pool/v1" in run.SHAPE_SETS[2:],
              str(run.SHAPE_SETS))
    )
    for fixture, source, expected in (("demo", "src/main/java", demo),
                                      ("demo-kotlin", "src/main/kotlin", kotlin)):
        project = {"name": fixture, "sources": [source]}
        found = target_ordering.mutation_targets(os.path.join(samples, fixture), project)
        failures += check(f"{fixture}'s default targets are unchanged", found == expected,
                          str(found))
        pool = pm.mutation_targets(os.path.join(samples, fixture), project)
        kinds = {kind for _, kind, _ in pool}
        failures += (
            check(f"{fixture}'s pool keeps every default target but the capped schema ones",
                  [t for t in expected if t[1] != "schema"] == [t for t in pool if t in expected
                                                                and t[1] != "schema"],
                  str(pool)) +
            check(f"{fixture}'s pool draws schema at most once",
                  sum(1 for t in pool if t[1] == "schema") <= 1, str(pool)) +
            check(f"{fixture} carries every pool family", set(pm.FAMILIES) <= kinds, str(kinds))
        )
    return failures


def case_a_rejected_draw_is_redrawn_from_its_own_family(root):
    """A Kotlin build with `allWarningsAsErrors` rejects a `void-return` edit: `if (true)` leaves
    the rest of the body unreachable, which kotlinc warns about. The gate must discard it, and the
    next draw must come from the same family, or that family is sampled less than the others."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import run  # noqa: PLC0415
    pm = _pool()
    write(root, "build.gradle.kts",
          "kotlin { compilerOptions { allWarningsAsErrors.set(true) } }\n")
    write(root, "src/main/kotlin/p/Counter.kt",
          "package p\n\nclass Counter {\n    private var n = 0\n\n"
          "    fun add(by: Int) {\n        n += by\n    }\n\n"
          "    fun reset() {\n        n = 0\n    }\n\n"
          "    fun total(): Int = n\n}\n")

    def compile_with(checkout, _project, _args, log_path):
        # A fake kotlinc under allWarningsAsErrors: code after `if (true) { return }` is
        # unreachable, a warning, and so an error.
        source = _read(os.path.join(checkout, "src/main/kotlin/p/Counter.kt"))
        unreachable = re.search(r"if \(true\) \{\n\s+return\n\s+\}\n\s+\S", source)
        with open(log_path, "w", encoding="utf-8") as handle:
            handle.write("e: Counter.kt: Unreachable code\nCompilation error. See log for more "
                         "details\n" if unreachable else "BUILD SUCCESSFUL\n")
        return (1 if unreachable else 0), 0.0

    items = [("src/main/kotlin/p/Counter.kt", "void-return", 0),
             ("src/main/kotlin/p/Counter.kt", "throw-entry", 0),
             ("src/main/kotlin/p/Counter.kt", "void-return", 1)]
    log = os.path.join(root, "compile.log")
    drawn = []
    for position, (relative, kind, index) in enumerate(items):
        before = _read(os.path.join(root, relative))
        pm.apply(root, relative, kind, index)
        _, rejected = run.compile_or_reject(root, {}, [], log, compile_with)
        drawn.append((kind, index, rejected))
        with open(os.path.join(root, relative), "w", encoding="utf-8") as handle:
            handle.write(before)
        if rejected:
            pm.redraw(items, position, kind)
    return (
        check("the gate rejects the void-return edit", drawn[0] == ("void-return", 0, True),
              str(drawn)) +
        check("the next draw is the same family's next target", drawn[1][:2] == ("void-return", 1),
              str(drawn)) +
        check("and the other family still follows", drawn[2][:2] == ("throw-entry", 0), str(drawn))
    )


def case_the_pool_composes_the_default_shapes_too(root):
    """`pool/v1` applies every default shape through `mutations.apply`, renaming included."""
    pm = _pool()
    subprocess.run(["git", "init", "--quiet", root], check=True)
    write(root, "src/main/kotlin/p/Gate.kt", "package p\n\nfun open(): Boolean = true\n")
    subprocess.run(["git", "-C", root, "add", "."], check=True)
    subprocess.run(["git", "-C", root, "-c", "user.email=t@e.com", "-c", "user.name=t",
                    "commit", "--quiet", "-m", "base"], check=True)
    label = pm.apply(root, "src/main/kotlin/p/Gate.kt", "renamed", 0)
    family = pm.apply(root, "src/main/kotlin/p/GateMoved.kt", "throw-entry", 0)
    return (
        check("a default shape is applied as the default set applies it",
              label == "src/main/kotlin/p/Gate.kt::renamed::Gate->GateMoved", str(label)) +
        check("a family is labelled with its kind",
              family == "src/main/kotlin/p/GateMoved.kt::throw-entry::open", str(family)) +
        check("an unknown kind applies nothing", pm.apply(root, "src/main/kotlin/p/GateMoved.kt",
                                                          "made-up", 0) is None, "it applied")
    )


CASES = (case_java_constant, case_kotlin_constant, case_string_constant,
         case_aliased_detection, case_rename, case_corpus_contains_all_four_shapes,
         case_inline_fun_is_reachable, case_block_bodied_inline_fun_is_mutated_by_index,
         case_numeric_literals_in_every_notation_a_guest_actually_writes,
         case_a_string_constant_containing_an_escaped_quote_still_compiles,
         case_the_injected_statement_carries_braces,
         case_the_recall_arm_shapes_are_never_in_the_default_grammar,
         case_each_recall_arm_shape_changes_what_it_names,
         case_the_recall_arm_shapes_bump_every_notation_and_only_nested_java_members,
         case_void_return_inserts_one_return_at_entry,
         case_throw_entry_throws_at_entry_whatever_the_return_type,
         case_constructors_initialisers_and_bodiless_methods_yield_no_target,
         case_records_comments_and_strings_yield_no_target,
         case_override_add_adds_one_override_to_a_class_that_lacks_it,
         case_member_delete_removes_exactly_one_override,
         case_the_pool_edits_carry_braces_and_no_typed_literal,
         case_the_default_set_is_unchanged_by_the_pool,
         case_a_rejected_draw_is_redrawn_from_its_own_family,
         case_the_pool_composes_the_default_shapes_too)


def test_every_mutation_shape_behaves():
    """The pytest entry point. Asserts, where the cases only count."""
    failures = 0
    for case in CASES:
        root = tempfile.mkdtemp(prefix="yoriwake-mut-")
        try:
            failures += case(root)
        finally:
            shutil.rmtree(root, ignore_errors=True)
    assert failures == 0, f"{failures} mutation-shape checks failed; run this file directly for detail"


def main():
    failures = 0
    for test in CASES:
        print(test.__name__)
        root = tempfile.mkdtemp(prefix="yoriwake-mut-")
        try:
            failures += test(root)
        finally:
            shutil.rmtree(root, ignore_errors=True)
    print(f"\n{'FAILED' if failures else 'all passed'}: {failures} failed checks")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
