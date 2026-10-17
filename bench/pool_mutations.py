"""
The `pool/v1` shape set: the default shapes, plus four families real commits make and the default
grammar never plants.

    void-return     an early return at the entry of a void Java method or a block-bodied Unit
                    Kotlin function. Half of the code-changing commits in the census change a void
                    or Unit body, and the default grammar edits only methods that return a value.
    throw-entry     an `IllegalStateException` thrown at entry, in the same `if (true)` form, for
                    every Java method and Kotlin function with a body, whatever it returns. It
                    reaches every test that executes the method, which is the question coverage
                    selection must answer.
    override-add    an override of `toString`, `hashCode` or `equals` added to a class that lacks
                    it, returning a fixed value. It changes dispatch for every caller, the behaviour
                    an added member changes.
    member-delete   one overriding method deleted, so dispatch falls back to the parent's. An
                    override of an abstract member does not compile, and the gate discards it.

Constructors, initialisers and declarations without a body yield no target. `schema` is capped at
one target per project: it changes behaviour only where a test introspects, and almost none does.

WHY A FILE OF ITS OWN. `grammar_digest` hashes the default grammar's files, and a sweep resumes only
rows drawn under the digest it would stamp now. Putting these generators in `mutations.py` or the
set in `target_ordering.py` would move the default set's digest and start every default results
file fresh, for a change that draws nothing new from the default set. This file is digested with
`pool/v1` only.

Every injection is written the way a person would, for the reason `recall_check.py` gives:
whatever quality gates the guest runs before its tests read it. Braces on their own lines, nothing
that only compiles at one width of literal, and no edit to a declaration whose `{` shares its line
with code.
"""

import glob
import hashlib
import os
import re

import mutations
import target_ordering

POOL_SHAPES = "pool/v1"

FAMILIES = ("void-return", "throw-entry", "override-add", "member-delete")

# Every family changes what a test that runs the method observes, so all four are answering shapes.
ANSWERING_SHAPES = target_ordering.ANSWERING_SHAPES + FAMILIES

SENTINEL = '"yoriwake-mutated"'

_JAVA_ANNOTATIONS = r"(?P<annotations>(?:@\w+(?:\([^)\n]*\))?\s+)*)"
# The modifiers a method with a body can carry. `abstract` and `native` are absent on purpose: a
# method that carries one has no body to edit.
_JAVA_MODIFIERS = r"(?:(?:public|protected|private|static|final|synchronized|strictfp|default)\s+)*"

# A Java method whose body opens at the end of its declaration. Indented, as members are.
JAVA_METHOD = re.compile(
    r"^(?P<indent>[ \t]+)" + _JAVA_ANNOTATIONS + _JAVA_MODIFIERS +
    r"(?:<[^\n;{}=]*?>\s+)?"
    r"(?P<type>[\w.$]+(?:\s*<[^\n;{}=()]*>)?(?:\s*\[\])*)\s+"
    r"(?P<name>\w+)\s*\([^)]*\)\s*(?:throws\s+[\w.$,\s]+?)?\s*\{[ \t]*$",
    re.MULTILINE,
)

# Words the type position can catch on a line that is not a method: a constructor reads as
# `public Foo(`, an anonymous class as `new Foo(`.
_JAVA_NOT_A_TYPE = {"public", "protected", "private", "static", "final", "synchronized", "strictfp",
                    "default", "abstract", "native", "new", "return", "throw", "else", "case",
                    "yield", "assert", "if", "for", "while", "switch", "catch", "do", "try"}

_KOTLIN_MODIFIERS = (r"(?P<modifiers>(?:(?:public|internal|private|protected|override|open|final|"
                     r"inline|suspend|operator|infix|tailrec|actual)\s+)*)")

# A Kotlin function with a body: `{` closing its line, or `=` and an expression.
KOTLIN_FUN = re.compile(
    r"^(?P<indent>[ \t]*)(?:@\w+(?:\([^)\n]*\))?\s+)*" + _KOTLIN_MODIFIERS +
    r"fun\s+(?:<[^>\n]*>\s*)?(?:[\w.<>\[\], ?]+\.)?(?P<name>\w+)\s*\([^)]*\)"
    r"(?:[ \t]*:[ \t]*(?P<type>[^={\n]+?))?[ \t]*(?P<open>\{|=)",
    re.MULTILINE,
)

JAVA_CLASS = re.compile(
    r"^(?P<indent>)(?:(?:public|final|abstract|strictfp)\s+)*class\s+(?P<name>\w+)\b[^{;]*\{[ \t]*$",
    re.MULTILINE,
)
KOTLIN_CLASS = re.compile(
    r"^(?P<indent>)(?P<modifiers>(?:(?:public|internal|private|open|abstract|sealed|final|data|value|"
    r"enum|annotation|inline|inner)\s+)*)class\s+(?P<name>\w+)",
    re.MULTILINE,
)
# Classes whose `toString`, `hashCode` and `equals` the compiler already writes, or forbids.
_KOTLIN_GENERATED = {"data", "value", "enum", "annotation", "inline"}

# Added in this order, the first one a class lacks. `equals` comes last because Error Prone's
# EqualsHashCode is an error, not a warning: an `equals` added to a class with no `hashCode` would
# be rejected by every guest that runs it.
OVERRIDES = {
    ".java": (
        ("toString", r"\bString\s+toString\s*\(\s*\)",
         ("@Override", "public String toString() {", f"    return {SENTINEL};", "}")),
        ("hashCode", r"\bint\s+hashCode\s*\(\s*\)",
         ("@Override", "public int hashCode() {", "    return 0;", "}")),
        # The parameter may carry `final` and annotations: `equals(@Nullable Object o)` is common.
        ("equals", r"\bboolean\s+equals\s*\(\s*(?:(?:final|@[\w.]+(?:\([^)]*\))?)\s+)*"
                   r"(?:java\.lang\.)?Object\s+\w+\s*\)",
         ("@Override", "public boolean equals(Object other) {", "    return true;", "}")),
    ),
    ".kt": (
        ("toString", r"\bfun\s+toString\s*\(\s*\)", (f"override fun toString(): String = {SENTINEL}",)),
        ("hashCode", r"\bfun\s+hashCode\s*\(\s*\)", ("override fun hashCode(): Int = 0",)),
        ("equals", r"\bfun\s+equals\s*\(", ("override fun equals(other: Any?): Boolean = true",)),
    ),
}


def _code(text):
    """`text` with comments, strings and char literals blanked, offsets kept, so braces count."""
    return mutations._NOT_CODE.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), text)


def _closing(code, opening):
    """The offset of the `}` matching the `{` at `opening`, or None."""
    depth = 0
    for position in range(opening, len(code)):
        if code[position] == "{":
            depth += 1
        elif code[position] == "}":
            depth -= 1
            if depth == 0:
                return position
    return None


def _line_end(text, position):
    end = text.find("\n", position)
    return len(text) if end < 0 else end


def _declarations(path, text):
    """Every method or function with a body, as `(match, kind)`; kind is `block` or `expression`.

    A Java method counts only when its `{` ends the line. A Kotlin expression body counts only when
    it is one line the declaration closes: the edit wraps it in braces, and an expression that runs
    on cannot be wrapped without understanding it.
    """
    if not path.endswith((".java", ".kt")):
        return []
    code = _code(text)
    # Matched against the text, kept only where the name is code: a declaration in a comment, a
    # text block or a raw string would be edited where the edit changes nothing, and still pass the
    # gate as a row of its family.
    matches = [m for m in (JAVA_METHOD if path.endswith(".java") else KOTLIN_FUN).finditer(text)
               if code[m.start("name")] != " "]
    if path.endswith(".java"):
        # A record's header reads as a method returning `record`. Not in `_JAVA_NOT_A_TYPE`, which
        # names are checked against too: a method may be called `record`.
        matches = [m for m in matches if m.group("type") != "record"]
        return [(m, "block") for m in matches
                if m.group("type") not in _JAVA_NOT_A_TYPE and m.group("name") not in _JAVA_NOT_A_TYPE]
    found = []
    for match in matches:
        rest = text[match.end():_line_end(text, match.end())]
        if match.group("open") == "{":
            if not rest.strip():
                found.append((match, "block"))
        elif _closed_expression(text, code, match):
            found.append((match, "expression"))
    return found


def _closed_expression(text, code, match):
    """Whether a Kotlin `= expression` is whole on the declaration's line."""
    end = _line_end(text, match.end())
    if not text[match.end():end].strip():
        return False
    # Read with strings blanked, so a bracket or an operator inside a string literal counts as
    # neither.
    expression = code[match.end():end].strip()
    if expression and expression[-1] in "{([,.=+-*/%&|?:<>!":
        return False
    pairs = (("(", ")"), ("[", "]"), ("{", "}"))
    if any(expression.count(a) != expression.count(b) for a, b in pairs):
        return False
    # A following line indented deeper is a continuation (`.map { }`, `?: other`), and wrapping only
    # the first line would cut the expression in two.
    for line in text[end + 1:].split("\n"):
        if line.strip():
            indent = len(line) - len(line.lstrip())
            return indent <= len(match.group("indent"))
    return True


def _entry(text, match, lines):
    """`text` with `lines` injected as the first statements of the block `match` opens."""
    indent = match.group("indent")
    injection = "".join("\n" + indent + "    " + line for line in lines)
    return text[:match.end()] + injection + text[match.end():]


def _write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def _read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


# ---------------------------------------------------------------------------------- void-return

def _void_declarations(path, text):
    """Void Java methods and block-bodied Unit Kotlin functions. An expression-bodied Unit function
    has no statements to return before, so it yields no target."""
    if path.endswith(".java"):
        return [m for m, _ in _declarations(path, text) if m.group("type") == "void"]
    return [m for m, kind in _declarations(path, text)
            if kind == "block" and (m.group("type") or "Unit").strip() == "Unit"]


def void_returns_in(path):
    return len(_void_declarations(path, _read(path)))


def void_return(path, index=0):
    """Returns at the entry of the index-th void method or Unit function, so its body never runs."""
    text = _read(path)
    matches = _void_declarations(path, text)
    if index >= len(matches):
        return None
    match = matches[index]
    statement = "return;" if path.endswith(".java") else "return"
    _write(path, _entry(text, match, ("if (true) {", "    " + statement, "}")))
    return match.group("name")


# ---------------------------------------------------------------------------------- throw-entry

def entries_in(path):
    return len(_declarations(path, _read(path)))


def throw_entry(path, index=0):
    """Throws at the entry of the index-th method or function with a body, whatever it returns.

    Thrown rather than returned, so the edit names no value of the declared type: a sentinel that
    has to be a valid literal of every return type is the defect class that cost mockito and rxjava
    their `long` constants.
    """
    text = _read(path)
    matches = _declarations(path, text)
    if index >= len(matches):
        return None
    match, kind = matches[index]
    if path.endswith(".java"):
        throw = f"throw new IllegalStateException({SENTINEL});"
    else:
        throw = f"throw IllegalStateException({SENTINEL})"
    if kind == "block":
        _write(path, _entry(text, match, ("if (true) {", "    " + throw, "}")))
        return match.group("name")
    # `fun f(): T = expression` becomes the conventional if-else expression body, braced, so the
    # original expression is still the value when the condition is false.
    indent = match.group("indent")
    end = _line_end(text, match.end())
    expression = text[match.end():end].strip()
    body = (f" if (true) {{\n{indent}    {throw}\n{indent}}} else {{\n"
            f"{indent}    {expression}\n{indent}}}")
    _write(path, text[:match.end()] + body + text[end:])
    return match.group("name")


# --------------------------------------------------------------------------------- override-add

def _class_bodies(path, text):
    """`(match, opening brace offset)` for each top-level class this family may add a member to."""
    code = _code(text)
    if path.endswith(".java"):
        return [(m, code.rindex("{", m.start(), m.end())) for m in JAVA_CLASS.finditer(code)]
    if not path.endswith(".kt"):
        return []
    found = []
    for match in KOTLIN_CLASS.finditer(code):
        if set(match.group("modifiers").split()) & _KOTLIN_GENERATED:
            continue
        opening = _kotlin_body_start(code, match.end())
        if opening is not None:
            found.append((match, opening))
    return found


def _kotlin_body_start(code, position):
    """The `{` that opens a Kotlin class body, or None when the class has none.

    The header may run over several lines (a constructor, a supertype list); it ends at a line that
    neither ends with `,` or `:` nor is followed by one starting with `:`, `,` or `where`.
    """
    depth = 0
    while position < len(code):
        char = code[position]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == "{" and depth <= 0:
            return position if not code[position + 1:_line_end(code, position)].strip() else None
        elif char == "\n" and depth <= 0:
            before = code[:position].rstrip()
            after = code[position + 1:].lstrip()
            if not (before.endswith((",", ":")) or after.startswith((":", ",", "where"))):
                return None
        position += 1
    return None


def _members(code, opening, closing):
    """The class body with everything nested deeper than its own members blanked."""
    out = []
    depth = 0
    for char in code[opening + 1:closing]:
        if char == "{":
            depth += 1
        if char == "}":
            depth -= 1
            out.append(" ")
            continue
        out.append(char if depth == 0 or char == "\n" else " ")
    return "".join(out)


def _missing_override(path, text):
    """`(class body opening, name, lines)` for the first override the file's first eligible class
    lacks, or None."""
    extension = os.path.splitext(path)[1]
    if extension not in OVERRIDES:
        return None
    code = _code(text)
    for _, opening in _class_bodies(path, text):
        closing = _closing(code, opening)
        if closing is None:
            continue
        members = _members(code, opening, closing)
        for name, declared, lines in OVERRIDES[extension]:
            if not re.search(declared, members):
                return opening, name, lines
        # The first class is the one the file is about; a later one is not drawn instead.
        return None
    return None


def can_override_add(path):
    return _missing_override(path, _read(path)) is not None


def override_add(path, index=0):
    """Adds the first of `toString`, `hashCode`, `equals` the file's first class lacks, returning a
    fixed value. One per file: index 0 only."""
    text = _read(path)
    found = _missing_override(path, text) if index == 0 else None
    if found is None:
        return None
    opening, name, lines = found
    end = _line_end(text, opening)
    following = text[end + 1:_line_end(text, end + 1)]
    member_indent = (following[:len(following) - len(following.lstrip())]
                     if following.strip() and following.strip() != "}" else "    ")
    block = "".join("\n" + member_indent + line for line in lines)
    # A blank line between the added member and the next one, as a formatter would leave it.
    spacer = "\n" if following.strip() and following.strip() != "}" else ""
    _write(path, text[:end] + block + spacer + text[end:])
    return name


# -------------------------------------------------------------------------------- member-delete

def _overrides(path, text):
    """Overriding methods, as `(start, end)` spans of whole lines to delete, with their names."""
    code = _code(text)
    found = []
    for match, kind in _declarations(path, text):
        if path.endswith(".java"):
            if "@Override" not in match.group("annotations"):
                continue
        elif "override" not in match.group("modifiers").split():
            continue
        start = match.start()
        if kind == "block":
            closing = _closing(code, text.rindex("{", match.start(), match.end()))
            if closing is None:
                continue
            end = _line_end(text, closing)
        else:
            end = _line_end(text, match.end())
        found.append((_with_leading_comment(text, start), end, match.group("name")))
    return found


def _with_leading_comment(text, start):
    """`start` moved up over a comment block that sits directly above the declaration, which would
    otherwise be left describing nothing -- a dangling Javadoc is a Checkstyle error."""
    lines = text[:start].split("\n")[:-1]
    taken = 0
    inside = False
    for line in reversed(lines):
        stripped = line.strip()
        # Entered only from a line that is all comment. Code with a trailing `/* ... */` ends the
        # walk; entered from there, every line up to the next `/*` would be taken as comment.
        if inside or (stripped.endswith("*/") and stripped.startswith(("/*", "*"))):
            inside = not stripped.startswith("/*")
            taken += 1
            continue
        if stripped.startswith("//"):
            taken += 1
            continue
        break
    # A comment whose opening was never found is not one this walk understood; delete nothing above.
    if taken == 0 or inside:
        return start
    return len("\n".join(lines[:len(lines) - taken])) + 1


def overrides_in(path):
    return len(_overrides(path, _read(path)))


def member_delete(path, index=0):
    """Deletes the index-th overriding method whole, with the comment directly above it."""
    text = _read(path)
    spans = _overrides(path, text)
    if index >= len(spans):
        return None
    start, end, name = spans[index]
    before, after = text[:start], text[end + 1:]
    # Two blank lines meeting where the method was would be a formatter's complaint; keep one.
    if before.endswith("\n\n") and (after.startswith("\n") or after.lstrip(" \t").startswith("}")):
        before = before[:-1]
    _write(path, before + after)
    return name


# --------------------------------------------------------------------------------------- the set

def _first_by_hash(targets):
    """The one target whose path hashes lowest. Neither alphabetical, which would favour
    `Abstract*` hubs, nor dependent on the sweep's ordering, which a resume would redraw."""
    if not targets:
        return []
    return [min(targets, key=lambda t: hashlib.sha256(t[0].encode("utf-8")).hexdigest())]


def mutation_targets(checkout, project):
    """Every `(path, kind, index)` `pool/v1` draws from: the default shapes, `schema` capped at one
    per project, and the four families."""
    default = target_ordering.mutation_targets(checkout, project, target_ordering.DEFAULT_SHAPES)
    targets = [t for t in default if t[1] != "schema"]
    targets += _first_by_hash([t for t in default if t[1] == "schema"])
    for relative in sorted(_source_files(checkout, project)):
        path = os.path.join(checkout, relative)
        targets += [(relative, "void-return", i) for i in range(void_returns_in(path))]
        targets += [(relative, "throw-entry", i) for i in range(entries_in(path))]
        if can_override_add(path):
            targets.append((relative, "override-add", 0))
        targets += [(relative, "member-delete", i) for i in range(overrides_in(path))]
    return targets


def _source_files(checkout, project):
    """The `.java` and `.kt` files under the project's source roots, as the default set globs them."""
    found = set()
    for source_root in project.get("sources") or []:
        base = os.path.join(checkout, source_root)
        if os.path.isfile(base):
            paths = [base]
        else:
            paths = (glob.glob(os.path.join(base, "**", "*.kt"), recursive=True) +
                     glob.glob(os.path.join(base, "**", "*.java"), recursive=True))
        found.update(os.path.relpath(p, checkout).replace("\\", "/") for p in paths)
    return found


def by_coverage(targets, covered):
    """`target_ordering.by_coverage`, with the four families counted as answering shapes."""
    _, provenance = target_ordering.by_coverage(targets, covered)

    def by_shape(bucket):
        answering = [t for t in bucket if t[1] in ANSWERING_SHAPES]
        other = [t for t in bucket if t[1] not in ANSWERING_SHAPES]
        return target_ordering.weave(target_ordering.interleave_shapes(answering),
                                     target_ordering.interleave_shapes(other))

    ordered = [target for where in ("covered", "unknown", "uncovered")
               for target in by_shape([t for t in targets if provenance[t] == where])]
    return ordered, provenance


GENERATORS = {"void-return": void_return, "throw-entry": throw_entry,
              "override-add": override_add, "member-delete": member_delete}


def apply(checkout, relative, kind, index):
    """`mutations.apply`, for the four families too."""
    generator = GENERATORS.get(kind)
    if generator is None:
        return mutations.apply(checkout, relative, kind, index)
    name = generator(os.path.join(checkout, relative), index)
    return None if name is None else f"{relative}::{kind}::{name}"


def redraw(items, position, kind):
    """Moves the next target of `kind` after `position` to just after it, so a draw the compile
    gate rejected is replaced from its own family. Without this, a family whose edits a guest
    rejects (a Kotlin build with `allWarningsAsErrors` rejects every `void-return`) is sampled less
    than the others, and nothing says so."""
    for later in range(position + 1, len(items)):
        if items[later][1] == kind:
            if later != position + 1:
                items.insert(position + 1, items.pop(later))
            return items[position + 1]
    return None
