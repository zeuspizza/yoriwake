"""
Mutations shaped like the defects that actually got through.

The original corpus replaced a Boolean function body with a constant, in a file whose name matched
its class, in a build with no coverage excludes. Thirteen such mutations reported zero missed
failures — and the selector was, at that moment, unsound in four ways none of them could reach. A
corpus that only contains the cases its author thought of reproduces its author's blind spots and
reports them as confidence.

Each generator here reproduces one shape in which scope is not proof of coverage:

    body            the original: a top-level single-expression Boolean function body replaced
                    with a constant. Narrower even than one shape -- it never reached a method
                    inside a class.
    constant        a compile-time constant's value. Inlined into every dependent, whose sources do
                    not change, while the holder may never be loaded at all.
    renamed         a file moved. Rename detection reports only the destination, so the class that
                    vanished from the old path can go unmentioned.
    aliased         a file whose declared type is not named after it, so a prefix derived from the
                    path names a class that does not exist.
    schema          a public method added to a class. Changes nothing any caller executes, and
                    everything a caller that INSPECTS the class sees -- the shape that defeats a
                    rule reasoning from the absence of coverage, because a test can read a class's
                    methods, fields and annotations without running a line of it.

`aliased` is a target *selector* rather than an edit: such files already exist in any Kotlin
codebase, and the point is to mutate those rather than to manufacture one.
"""

import os
import re
import subprocess

# `static final int X = 7;`, `const val X = 7`, `val X = "s"` — a literal initialiser on a constant.
#
# THE STRING ALTERNATIVE UNDERSTANDS ESCAPES, and it did not at first. `"[^"]*"` cannot see
# an escaped quote, so on apache-groovy's `SIMPLE_ESCAPES = "\"\\/bfnrt"` it matched a truncated
# span, the replacement left `\\/bfnrt";` dangling, and `:groovy-json:compileJava` failed with
# "unclosed string literal". `assert_recall.py` exits 1 on any build-failed row, so ONE String
# constant carrying a `\"` cost that project its whole sweep -- the same shape as the braceless
# `if (true) return` that cost opentelemetry-java three rows, in the other generator.
JAVA_CONSTANT = re.compile(
    r"(?m)^(?P<head>[^\S\n]*(?:public|private|protected)?\s*static\s+final\s+"
    r"(?:int|long|short|byte|double|float|String)\s+\w+\s*=\s*)(?P<value>\"(?:\\.|[^\"\\])*\"|-?(?:0[xXbB][0-9a-fA-F_]+|\d[\d_]*)[lLuUfFdD]?)"
)

# Inside an interface, `int MAX = 7;` is implicitly public static final -- which is how a
# constants-only interface is actually written, and the shape the first version of this generator
# missed entirely while reporting that it had found nothing to mutate.
JAVA_INTERFACE_CONSTANT = re.compile(
    r"(?m)^(?P<head>[^\S\n]+(?:int|long|short|byte|double|float|String)\s+\w+\s*=\s*)"
    r"(?P<value>\"(?:\\.|[^\"\\])*\"|-?(?:0[xXbB][0-9a-fA-F_]+|\d[\d_]*)[lLuUfFdD]?)\s*;"
)
KOTLIN_CONSTANT = re.compile(
    r"(?m)^(?P<head>[^\S\n]*(?:public|private|internal)?\s*const\s+val\s+\w+(?:\s*:\s*\w+)?\s*=\s*)"
    r"(?P<value>\"(?:\\.|[^\"\\])*\"|-?(?:0[xXbB][0-9a-fA-F_]+|\d[\d_]*)[lLuUfFdD]?)"
)

INTERFACE_DECLARATION = re.compile(r"(?m)^\s*(?:\w+\s+)*interface\s+\w+")

TYPE_DECLARATION = re.compile(
    r"(?m)^[^\S\n]*(?:(?:public|private|internal|protected|abstract|final|open|sealed|data|value"
    r"|inner|static|annotation|enum|fun)\s+)*(?:class|interface|object|record|@interface|enum)\s+"
    r"(?P<name>\w+)"
)


def _read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def _write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def _bumped(literal):
    r"""One more than a numeric literal, whatever base, separators or suffix it was written in.

    `int(literal) + 1` handled `7` and nothing else. okhttp declares
    `const val TAG_CLASS_UNIVERSAL = 0b0000_0000`; the old value pattern matched only the leading
    `0`, the replacement left `b0000_0000` dangling, and `:okhttp-tls:compileKotlin` failed with
    "Unresolved reference". `assert_recall.py` exits 1 on any build-failed row, so ONE binary literal
    cost okhttp its whole sweep -- the same shape as the escaped quote that cost apache-groovy one
    and the one-line braces that cost kafka eight rows.

    Returns a decimal KEEPING THE ORIGINAL SUFFIX, and the suffix is the whole point. Dropping it
    was safe reasoning about the wrong half of the problem: an int literal assigned to a long field
    does widen, but a literal too big to BE an int is a compile error before any widening is
    considered. mockito and rxjava each declare five constants like
    `private static final long serialVersionUID = -1172445254382395149L`, and bumping that to a bare
    `-1172445254382395148` gives "error: integer number too large" -- five build-failed rows apiece,
    in a corpus re-sweep that had already cost two days.

    The notation itself still need not survive: `0x1F` may become `32`, because a hex literal and
    its decimal value are the same int. Only the suffix carries type information the compiler needs.

    `_flip`'s own `-?\d+` is deliberately NOT this: it inverts an inline function BODY, where a plain
    decimal is the only shape it claims to handle, and widening that grammar would change which
    targets the recall arm draws.
    """
    text = literal.strip().replace("_", "")
    negative = text.startswith("-")
    if negative:
        text = text[1:]
    # THE BASE IS READ BEFORE ANY SUFFIX IS STRIPPED. Doing it the other way round ate the `F` of
    # `0x1F` as a float suffix and turned 31 into 1 -- `d`, `f` and `l` are all hex DIGITS, so a hex
    # literal may only shed `l`, `L`, `u` or `U`.
    # The radix prefix as written, taken from the sign-stripped text: slicing the literal itself
    # read `-0` off `-0x1F` and emitted `--020`, which neither compiler accepts.
    radix, base = text[:2], 10
    prefix = radix.lower()
    if prefix == "0x":
        base, text = 16, text[2:]
    elif prefix == "0b":
        base, text = 2, text[2:]
    suffixes = "lLuU" if base == 16 else "lLuUfFdD"
    suffix = ""
    while text and text[-1] in suffixes:
        # Kept, not discarded: it is what tells the compiler the literal is a long or a float, and
        # a value outside int range is rejected outright without it.
        suffix = text[-1] + suffix
        text = text[:-1]

    # A HEX OR BINARY LITERAL IS RE-EMITTED IN ITS OWN BASE, AT ITS OWN WIDTH, and that is not
    # cosmetic. Java lets a hex literal fill the full unsigned width and wrap into the signed range;
    # a decimal literal may not. rxjava declares `private static final int INT_PHI = 0x9E3779B9`,
    # which is -1640531527 as a signed int -- rendering the bump as decimal 2654435770 gives
    # "error: integer number too large", and `0x8000000000000000L` (Long.MIN_VALUE) overflows even
    # with the suffix kept. Holding the digit count fixed makes the result representable wherever
    # the original was, and makes the top of the range wrap the way the language already does.
    if base != 10:
        digits = len(text)
        value = (int(text, base) + 1) % (base ** digits)
        body = format(value, "x" if base == 16 else "b").rjust(digits, "0")
        # Case follows the source. Purely cosmetic to the compiler, and the whole point of this
        # generator is that the only difference in the diff is the one it meant to make.
        if any(c.isupper() for c in text):
            body = body.upper()
        return f"{'-' if negative else ''}{radix}{body}{suffix}"

    # The sign is applied BEFORE the increment. Negating afterwards turned -3 into -4 rather than
    # -2, which is still a different value and would have compiled -- a mutation that is wrong in a
    # way no build catches is the worst shape this generator can produce.
    value = (-int(text, base) if negative else int(text, base)) + 1
    # AND THE TOP OF THE DECIMAL RANGE GOES THE OTHER WAY. `int x = 2147483647` is legal and
    # `2147483648` is not, so at the boundary the mutation steps down instead of up: the point is
    # that the value DIFFERS, and there is no direction requirement anywhere in this generator.
    ceiling = 2 ** 63 - 1 if suffix.lower().startswith("l") else 2 ** 31 - 1
    if value > ceiling:
        value -= 2
    return f"{value}{suffix}"


def constant(path, index=0):
    """Changes the index-th compile-time constant's value.

    The dangerous shape: javac and kotlinc inline these into every dependent, so the dependents'
    bytecode changes while their sources do not — they are absent from the change set entirely — and
    the holder itself may never be loaded at runtime. Absence from a loaded-class union is then a
    true fact that proves nothing.
    """
    text = _read(path)
    matches = list(_constant_pattern(path, text).finditer(text))
    if index >= len(matches):
        return None
    match = matches[index]
    old = match.group("value")
    new = '"yoriwake-mutated"' if old.startswith('"') else _bumped(old)
    mutated = text[: match.start("value")] + new + text[match.end("value"):]
    _write(path, mutated)
    return match.group(0).split("=")[0].strip().split()[-1]


def renamed(path, checkout, index=0):
    """Moves a file without touching its contents.

    `git diff --name-only` with rename detection on reports only the destination, so the class that
    vanished from the old path is never named. Kotlin only: a public Java type must be named after
    its file, so moving one does not compile.
    """
    if not path.endswith(".kt"):
        return None
    directory, filename = os.path.split(path)
    stem, extension = os.path.splitext(filename)
    destination = os.path.join(directory, stem + "Moved" + extension)
    if os.path.exists(destination):
        return None
    result = subprocess.run(["git", "-C", checkout, "mv", os.path.relpath(path, checkout),
                             os.path.relpath(destination, checkout)],
                            capture_output=True, text=True)
    if result.returncode != 0:
        return None
    return stem + "->" + stem + "Moved"


CLASS_BODY_START = re.compile(
    r"(?m)^(?P<head>[^\S\n]*(?:public\s+)?(?:final\s+|abstract\s+)?class\s+\w+[^{]*)\{"
)


def schema(path, index=0):
    """Adds a public method to the index-th class, changing its shape and nothing it does.

    The shape no other generator here can produce, and the one the coverage-absence rule is most
    likely to get wrong. A test that calls this class is unaffected -- the added method is never
    invoked, so no behaviour changes and no probe fires differently. A test that *introspects* it
    fails immediately. kestra's documentation and JSON-schema tests are exactly that: they load a
    class, read its members, and assert on what they found, which coverage cannot see at all.

    Java only. A rule that narrows on absence refuses Kotlin outright -- inline members are copied
    into their call sites -- so a Kotlin variant would exercise a path that always forces anyway.
    """
    if not path.endswith(".java"):
        return None
    text = _read(path)
    matches = list(CLASS_BODY_START.finditer(text))
    if index >= len(matches):
        return None
    match = matches[index]
    added = "\n\n    public int yoriwakeAddedMethod() {\n        return 1;\n    }"
    mutated = text[: match.end()] + added + text[match.end():]
    _write(path, mutated)
    return "yoriwakeAddedMethod"


def has_class_body(path):
    return path.endswith(".java") and bool(CLASS_BODY_START.search(_read(path)))


# ------------------------------------------------------------------ the recall-arm-only shapes
#
# THESE ARE NOT IN THE DEFAULT GRAMMAR, and that is the point rather than an oversight. The sweep,
# the recall gate and the target ordering share this module, so a shape added to the default changes
# the population every future sweep samples and makes figures taken before and after incomparable.
# That bias has already been seen running the other way, at 5 of
# 16 kafka targets changing a constant against a real history that does so far less often.
#
# What they have in common is the CONSUMER-RECOMPILE case: each one changes a declaration the
# compiler copies into, or names from, somewhere a change set derived from `git diff` never
# mentions. That is the shape the digest rule exists to catch, and none of the five default shapes
# produces it except `constant`, which produces it only sometimes.

KOTLIN_INLINE_FUN = re.compile(
    r"(?m)^(?P<head>[^\S\n]*(?:public|private|internal)?\s*inline\s+fun\s+(?P<name>\w+)[^=\n]*=\s*)"
    r"(?P<body>[^\n]+)$"
)


def inline_body(path, index=0):
    """Changes the body of a Kotlin `inline fun`.

    THE MISS THAT MOTIVATED ALL OF THIS. The compiler copies an inline body into every call site, so
    the code that actually runs lives in the CALLER's bytecode and no probe ever links the two. A
    test that calls it is recompiled and its source does not change, so it is absent from the change
    set entirely -- which is exactly what `git diff` cannot tell you and the compiler need not be
    asked.

    Single-expression bodies only. A block body would need its statements understood; an expression
    body can be replaced wholesale with something that still compiles and behaves differently.
    """
    if not path.endswith(".kt"):
        return None
    text = _read(path)
    matches = list(KOTLIN_INLINE_FUN.finditer(text))
    if index >= len(matches):
        return None
    match = matches[index]
    body = match.group("body").strip()
    replacement = _flip(body)
    if replacement is None:
        return None
    mutated = text[: match.start("body")] + replacement + text[match.end("body"):]
    _write(path, mutated)
    return match.group("name")


def has_inline(path):
    """Only an inline body [inline_body] can actually flip.

    The predicate used to ask the weaker question -- "does an inline fun exist" -- so a file whose
    only inline body is an expression `_flip` cannot invert registered a target that the generator
    then declined. A target that applies to nothing is reported as a row that asked nothing, which
    reads exactly like a shape that could not fail. `has_second_type` already mirrors its generator
    this way.
    """
    if not path.endswith(".kt"):
        return False
    return any(_flip(m.group("body").strip()) is not None
               for m in KOTLIN_INLINE_FUN.finditer(_read(path)))


def _flip(body):
    """An expression that compiles where `body` did and answers differently, or None.

    Deliberately narrow. A generator that guesses at arbitrary expressions produces trees that do
    not compile, and a mutation that does not compile is reported as "asked nothing" -- which reads
    exactly like a shape that could not fail.
    """
    if body in ("true", "false"):
        return "false" if body == "true" else "true"
    if re.fullmatch(r"-?\d+", body):
        return str(int(body) + 1)
    # `n > 0`, `n < 10`, `x >= y` -- invert the comparison and keep both operands.
    flipped = {">": "<=", "<": ">=", ">=": "<", "<=": ">", "==": "!=", "!=": "=="}
    match = re.fullmatch(r"(?P<left>[\w.()\s]+?)\s*(?P<op><=|>=|==|!=|<|>)\s*(?P<right>[\w.()\s]+)",
                         body)
    if match:
        return f"{match.group('left').strip()} {flipped[match.group('op')]} {match.group('right').strip()}"
    return None


# A constant declared INSIDE something -- a companion object, a nested class, an enclosing type --
# rather than at the top of a file. Matched by its indentation, which is what tells the two apart
# without parsing: a top-level declaration starts at column zero.
NESTED_CONSTANT = re.compile(
    r"(?m)^(?P<head>[^\S\n]+(?:public|private|protected|internal)?\s*"
    r"(?:static\s+final\s+(?:int|long|short|byte|double|float|String)|const\s+val)\s+(?P<name>\w+)"
    r"(?:\s*:\s*\w+)?\s*=\s*)(?P<value>\"(?:\\.|[^\"\\])*\"|-?(?:0[xXbB][0-9a-fA-F_]+|\d[\d_]*)[lLuUfFdD]?)"
)


# What cannot hold a structural brace: comments, text blocks, strings and char literals. Blanked
# before braces are counted, so a `"{"` in a string does not open a scope.
_NOT_CODE = re.compile(r'//[^\n]*|/\*.*?\*/|"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])+\'',
                       re.S)


def _nested_constants(path, text):
    """`NESTED_CONSTANT` matches that really sit inside a nested type.

    Indentation is enough in Kotlin, where an indented `const val` can only live in an object. In
    Java it is not: every member of the file's own top-level class is indented too, and a
    `static final` there is the ordinary `constant` shape under another name -- a row that never
    exercises the `Foo$Nested` blind spot this shape is named for. So a Java match must sit at
    brace depth two or deeper: inside a type that is itself inside the top-level one.
    """
    matches = list(NESTED_CONSTANT.finditer(text))
    if path.endswith(".kt"):
        return matches
    code = _NOT_CODE.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), text)
    return [m for m in matches
            if code.count("{", 0, m.start()) - code.count("}", 0, m.start()) >= 2]


def nested_constant(path, index=0):
    """Changes a constant declared inside a companion object or a nested type.

    `classesFor` has five known blind spots, and two of them are this: a
    companion object compiles to `Foo$Companion` and a nested class to `Foo$Nested`, so the constant
    that was copied into consumers lives in a class the changed PATH does not name. The top-level
    case is already the `constant` shape; this is the one the prefix derivation has to get right.
    """
    text = _read(path)
    matches = _nested_constants(path, text)
    if index >= len(matches):
        return None
    match = matches[index]
    old = match.group("value")
    new = '"yoriwake-mutated"' if old.startswith('"') else _bumped(old)
    _write(path, text[: match.start("value")] + new + text[match.end("value"):])
    return match.group("name")


def has_nested_constant(path):
    return bool(_nested_constants(path, _read(path)))


def second_type(path, index=0):
    """Changes a constant or a body in the SECOND top-level type declared in one file.

    The third of those blind spots this arm can reach. A prefix derived from a changed path
    names the type the file is named after; a second top-level type in the same file compiles to a
    class that prefix never matches, and Kotlin allows any number of them. The change set then omits
    a class the compiler certainly recompiled.
    """
    text = _read(path)
    declarations = _top_level_types(text)
    if len(declarations) < 2:
        return None
    after = declarations[1].end()
    # One pattern, not two. `_constant_pattern` already returns the Kotlin one for a `.kt` file and
    # a Java one otherwise, so a second KOTLIN_CONSTANT pass could never match for either.
    for match in _constant_pattern(path, text).finditer(text):
        if match.start() < after:
            continue
        old = match.group("value")
        new = '"yoriwake-mutated"' if old.startswith('"') else _bumped(old)
        _write(path, text[: match.start("value")] + new + text[match.end("value"):])
        return declarations[1].group("name")
    return None


def _top_level_types(text):
    """Type declarations at column zero.

    TOP-LEVEL, matched by indentation, because that is the whole point of the shape: a prefix
    derived from a changed path names the type the file is named after, and a SECOND top-level type
    compiles to a class that prefix never matches. `TYPE_DECLARATION` allows leading whitespace, so
    without this filter the "second type" was routinely a NESTED class -- which the path prefix does
    match, through `Foo$Nested`, and which is the `nested-constant` shape's job instead.
    """
    return [m for m in TYPE_DECLARATION.finditer(text)
            if m.group(0)[:len(m.group(0)) - len(m.group(0).lstrip())] == ""]


def has_second_type(path):
    text = _read(path)
    declarations = _top_level_types(text)
    if len(declarations) < 2:
        return False
    after = declarations[1].end()
    return any(m.start() >= after for m in _constant_pattern(path, text).finditer(text))


def declared_types(path):
    """Every top-level type a file declares."""
    return [m.group("name") for m in TYPE_DECLARATION.finditer(_read(path))]


def is_aliased(path):
    """Whether a file declares types but none named after the file.

    A prefix derived from the path then names a class that does not exist, so every rule downstream
    reasons confidently about nothing. This is failure mode one, and no mutation in the original
    corpus could produce it because every file it touched was named after its class.
    """
    stem = os.path.splitext(os.path.basename(path))[0]
    names = declared_types(path)
    return bool(names) and stem not in names


def _constant_pattern(path, text):
    if path.endswith(".kt"):
        return KOTLIN_CONSTANT
    if JAVA_CONSTANT.search(text):
        return JAVA_CONSTANT
    # Bare fields count only inside an interface, where they are constants by definition. Elsewhere
    # they are instance state, and changing one is an ordinary edit rather than an inlined one.
    return JAVA_INTERFACE_CONSTANT if INTERFACE_DECLARATION.search(text) else JAVA_CONSTANT


def has_constant(path):
    text = _read(path)
    return bool(_constant_pattern(path, text).search(text))


def can_rename(path):
    return path.endswith(".kt")
