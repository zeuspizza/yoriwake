"""
Renders the README's two benchmark charts as SVG from the sweep's results files. A STUB for a
dry run: it proves the sweep's output format carries what the charts need. The finished
charts, captions and styling are separate work.

Chart 1, `selected-share.svg`: per project that ran under 100% of the suite on at least one change,
a dot for each such change at the % it ran (`selected_distinct / full_distinct`), and how many of
its changes ran 100%; how many projects ran 100% on every change is a line under the rows. Chart 2, `time-saved.svg`: per project, the median share of the full arm's time
each change saved (`1 - selected_seconds / full_seconds`) against the suite's length
(`baseline_seconds`, log scale); below the zero line selection cost more than it saved, and a median
below -50% sits under a break in the axis with its value written out.
Each is written in a light and a `-dark` variant.

    python bench/readme_charts.py --out bench/scratch/charts                   # results/*.json
    python bench/readme_charts.py --arm isolated --out bench/scratch/charts
    python bench/readme_charts.py --sample --out bench/scratch/charts          # synthetic rows

`--sample` draws from SYNTHETIC rows, and every chart it writes says so in its title. Rows that
asked nothing (`status` other than `checked` or `no-effect`, `invalid` among them) or carry no
counts are left out, and how many were left out, and why, is printed.
"""

import argparse
from collections import Counter
import glob
import json
import math
import os
import random
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import corpus_state  # noqa: E402  (one definition of a row where a miss was possible)
RESULTS = os.path.join(HERE, "results")

# The reference palette's single series hue and surfaces, per mode (dataviz references/palette.md).
THEMES = {
    "light": {"surface": "#fcfcfb", "text": "#0b0b0b", "muted": "#52514e", "grid": "#e4e3df",
              "series": "#2a78d6"},
    "dark": {"surface": "#1a1a19", "text": "#ffffff", "muted": "#c3c2b7", "grid": "#383835",
             "series": "#3987e5"},
}
FONT = "font-family='-apple-system, Segoe UI, Helvetica, Arial, sans-serif'"
ANSWERED = ("checked", "no-effect")
# The six projects whose both arms were timed on every row (the sweep's lighter 0.1.0 rules). Chart
# 2 draws these and no others: `recall_only` alone admits every isolated file and every pre-flag one.
TIMING_SUBSET = ("grpc-java", "apache-groovy", "mockito", "spring-boot", "rxjava", "bitwarden-android")


def load(arm, paths=None, commit=None):
    """{project: {"rows": [...], "baseline_seconds": s}} from the arm's results files.

    Files stamped with another `plugin_commit` than `commit` are left out and named; without
    `commit`, more than one stamp across the files is refused, since one chart is one measurement.
    """
    suffix = ".isolated.json" if arm == "isolated" else ".json"
    projects, skipped, stamps, sources = {}, {}, {}, {}
    for path in sorted(paths or glob.glob(os.path.join(RESULTS, "*" + suffix))):
        if arm == "shared" and path.endswith(".isolated.json"):
            continue
        try:
            with open(path, encoding="utf-8") as handle:
                data = json.load(handle)
        except (OSError, ValueError):
            continue
        if not isinstance(data, dict) or "mutations" not in data:
            continue
        stamp = data.get("plugin_commit")
        if commit and stamp != commit:
            print(f"left out {os.path.basename(path)}: plugin_commit {stamp}, not {commit}",
                  file=sys.stderr)
            continue
        # A PLANTED-MISS CONTROL'S ROWS MEASURED A PLUGIN WITH A DEFECT PUT IN ON PURPOSE.
        if data.get("control") or any(row.get("control") for row in data.get("mutations") or []):
            raise SystemExit(f"{os.path.basename(path)} holds planted-miss control rows; a chart "
                             f"may not draw them")
        # A SETUP-FORCED PROJECT'S ROWS COUNT TOWARD NO FIGURE: every one forced for a cause
        # outside its change.
        if data.get("project_outcome") == "setup-forced":
            why = "rows of a setup-forced project"
            skipped[why] = skipped.get(why, 0) + len(data.get("mutations") or [])
            continue
        stamps.setdefault(stamp, []).append(os.path.basename(path))
        rows = []
        for row in data.get("mutations") or []:
            if row.get("status") in ANSWERED and row.get("full_distinct") \
                    and isinstance(row.get("selected_distinct"), int):
                rows.append(row)
            else:
                why = (f"status {row.get('status')}" if row.get("status") not in ANSWERED
                       else "no test counts")
                skipped[why] = skipped.get(why, 0) + 1
        if rows:
            name = data.get("project") or os.path.basename(path)
            # Two files naming one project (detekt.json, detekt-mutations.json) must not resolve by
            # whichever sorts last.
            if name in projects:
                raise SystemExit(f"{name} is in both {sources[name]} and {os.path.basename(path)}")
            sources[name] = os.path.basename(path)
            projects[name] = {
                "rows": rows, "baseline_seconds": data.get("baseline_seconds"),
                "recall_only": data.get("recall_only"),
                # None: every row of a timing sweep was timed, as before the cap.
                "timing_cap": data.get("timing_cap")}
    if len(stamps) > 1:
        raise SystemExit("files from more than one plugin_commit; pass --commit: " + "; ".join(
            f"{stamp}: {', '.join(names)}" for stamp, names in sorted(stamps.items(), key=str)))
    return projects, skipped


def load_release(path):
    """`load` over a published sweep's release asset: its merged results files, at one plugin
    commit, which `load` refuses to mix."""
    paths = [p for p in glob.glob(os.path.join(path, "merged", "**", "*.json"), recursive=True)
             if os.path.basename(p) != "sweep.json"]
    if not paths:
        raise SystemExit(f"no merged results files under {path}/merged")
    return load("shared", paths)


def read_codebases(path=os.path.join(HERE, "codebases.json")):
    """{project: codebase}: projects that share a repository, and the five Spring projects, are one
    codebase, so a count of projects does not read as a count of independent code."""
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def _plural(count, word):
    return f"{count} {word}{'' if count == 1 else 's'}"


def headline(projects, codebases):
    """The recall headline: missed failures against the failures induced, over the changes where a
    miss was possible, with the undetermined count, and projects counted with their codebases.
    No time figure: time is quoted per project, as a ratio of two arms from one job."""
    absent = sorted(name for name in projects if name not in codebases)
    if absent:
        raise SystemExit(f"no codebase for {', '.join(absent)} in codebases.json; a headline counts "
                         f"projects with their codebases")
    possible = {name: [r for r in p["rows"] if corpus_state.miss_was_possible(r)]
                for name, p in projects.items()}
    asked = sum(len(rows) for rows in possible.values())
    if not asked:
        raise SystemExit("no change where a miss was possible; a recall headline over none says "
                         "nothing")
    rows = [r for p in projects.values() for r in p["rows"]]
    missed = sorted(test for r in rows for test in r.get("missed") or [])
    # Over the changes that could miss only: a forced change's failures were never at risk.
    induced = sum(r.get("full_failures") or 0 for rows_ in possible.values() for r in rows_)
    undetermined = sum(len(r.get("undetermined") or []) + len(r.get("shared_key") or [])
                       for r in rows)
    where = [name for name, rows in possible.items() if rows]
    text = (f"{_plural(len(projects), 'project')} from "
            f"{_plural(len(set(codebases[n] for n in projects)), 'codebase')}: "
            f"{len(missed)} missed of {induced} failures the edits induced, across "
            f"{_plural(asked, 'change')} where a miss was possible (in "
            f"{_plural(len(where), 'project')} from "
            f"{_plural(len(set(codebases[n] for n in where)), 'codebase')}); "
            f"{undetermined} undetermined.")
    return text + (f" Missed: {', '.join(missed)}." if missed else "")


def codebase_breakdown(projects, codebases):
    """One row per codebase, beside the per-project table: projects that share a codebase are one
    body of code, so their changes are read together."""
    grouped = {}
    for name in sorted(projects):
        grouped.setdefault(codebases[name], []).append(name)
    table = []
    for codebase, names in sorted(grouped.items()):
        rows = [r for n in names for r in projects[n]["rows"]]
        table.append({"codebase": codebase, "projects": names, "changes": len(rows),
                      "miss_possible": sum(1 for r in rows if corpus_state.miss_was_possible(r)),
                      "missed": sum(len(r.get("missed") or []) for r in rows)})
    return table


def left_out(skipped):
    """How many rows were not drawn, and why: `status invalid` is a selecting arm that decided nothing."""
    total = sum(skipped.values())
    if not total:
        return "0 rows left out"
    return f"{total} rows left out (" + ", ".join(
        f"{count} {why}" for why, count in sorted(skipped.items())) + ")"


def sample():
    """SYNTHETIC rows in the results format, for exercising the charts without a sweep."""
    rng = random.Random(20260928)
    projects = {}
    for index, name in enumerate(("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")):
        suite = 20 * (2.4 ** index)
        full = 100 + 400 * index
        rows = []
        for _ in range(12):
            share = min(1.0, max(0.002, rng.betavariate(0.6 + index * 0.2, 2.5)))
            selected = max(1, round(share * full))
            fixed = 6 + rng.random() * 4
            rows.append({"status": "checked", "selected_distinct": selected, "full_distinct": full,
                         "full_seconds": round(suite + fixed, 1),
                         "selected_seconds": round(suite * selected / full + fixed + 3, 1)})
        projects[name] = {"rows": rows, "baseline_seconds": round(suite, 1), "synthetic": True}
    return projects


def _svg(width, height, theme, title, body):
    t = THEMES[theme]
    return (f"<svg xmlns='http://www.w3.org/2000/svg' width='{width}' height='{height}' "
            f"viewBox='0 0 {width} {height}' role='img' aria-label='{_esc(title)}'>"
            f"<title>{_esc(title)}</title>"
            f"<rect width='100%' height='100%' fill='{t['surface']}'/>"
            f"<text x='16' y='26' {FONT} font-size='15' font-weight='600' fill='{t['text']}'>"
            f"{_esc(title)}</text>{body}</svg>\n")


def _esc(text):
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") \
        .replace("'", "&#39;")


def _minus(text):
    return text.replace("-", "−")


def _width(text, size):
    """A conservative width estimate for sans-serif text, to keep labels inside the plot."""
    return len(text) * size * 0.6


def share_chart(projects, theme, note):
    t = THEMES[theme]
    counts = {name: [(r["selected_distinct"], r["full_distinct"]) for r in p["rows"]]
              for name, p in projects.items()}
    # WHOLE-SUITE CHANGES ARE COUNTED, NOT PLOTTED: most changes run the whole suite, and dots
    # stacked at 100% beside ones at 99.7% read as one blob whatever their labels say. A row shows
    # the changes that ran under 100% at the % they ran, and how many of its changes ran 100%.
    fewer = {name: sorted(s / f for s, f in c if s < f) for name, c in counts.items()}
    whole = {name: sum(1 for s, f in c if s >= f) for name, c in counts.items()}
    # A PROJECT THAT NEVER NARROWED IS NAMED, NOT DRAWN: its row would be an empty strip, so it is
    # counted under the rows and listed by name in the README's caption.
    narrowed = [name for name in counts if fewer[name]]
    order = sorted(narrowed, key=lambda n: (whole[n] / len(counts[n]),
                                            statistics.median(s / f for s, f in counts[n]), n))
    width, row_h, top = 760, 18, 76
    plot_l, plot_r, count_r = 180, 600, 744
    bottom = top + row_h * len(order)
    height = bottom + 50

    def x(value):
        return plot_l + value * (plot_r - plot_l)

    body = []
    for tick in (0, 0.25, 0.5, 0.75, 1.0):
        body.append(f"<line x1='{x(tick):.1f}' x2='{x(tick):.1f}' y1='{top - 4}' "
                    f"y2='{bottom}' stroke='{t['grid']}' stroke-width='1'/>"
                    f"<text x='{x(tick):.1f}' y='{bottom + 16}' {FONT} font-size='11' "
                    f"fill='{t['muted']}' text-anchor='middle'>{int(tick * 100)}%</text>")
    body.append(f"<text x='{plot_l}' y='{top - 10}' {FONT} font-size='11' "
                f"fill='{t['muted']}'>% of the suite run</text>")
    for index, name in enumerate(order):
        y = top + index * row_h + row_h / 2
        total = len(counts[name])
        body.append(f"<text x='{plot_l - 8}' y='{y + 4:.1f}' {FONT} font-size='11' "
                    f"fill='{t['text']}' text-anchor='end'>{_esc(name)}</text>"
                    f"<text x='{count_r}' y='{y + 4:.1f}' {FONT} font-size='11' "
                    f"fill='{t['text']}' text-anchor='end'>{whole[name]} of {total} ran 100%"
                    f"<title>{_esc(name)}: {whole[name]} of {total} changes ran the whole "
                    f"suite</title></text>")
        for value in fewer[name]:
            body.append(f"<circle cx='{x(value):.1f}' cy='{y:.1f}' r='4' "
                        f"fill='{t['series']}' stroke='{t['surface']}' stroke-width='1.5'>"
                        f"<title>{_esc(name)}: {value:.1%} of the suite</title></circle>")
    body.append(f"<text x='16' y='46' {FONT} font-size='12' fill='{t['muted']}'>each dot is one "
                f"change, at the % of the suite it ran; right, how many of the project's changes "
                f"ran 100%</text>")
    others = len(counts) - len(order)
    if others:
        body.append(f"<text x='16' y='{height - 10}' {FONT} font-size='11' fill='{t['muted']}'>"
                    f"not drawn: {others} other project{'s' if others != 1 else ''} ran 100% of "
                    f"the suite on every change</text>")
    return _svg(width, height, theme,
                f"Projects where at least one change ran under 100% of the suite{note}",
                "".join(body))


# A median below this is drawn in a band under a break in the y-axis, with its value written beside
# it: on one linear scale a single outlier squeezes every other point into a sliver, and a log or
# symlog scale would understate its distance from zero while still looking continuous.
OFF_SCALE = -0.5


def timing_projects(projects):
    """The projects chart 2 draws: the sweep's timing subset, never a recall-only file."""
    # A RECALL-ONLY FILE TIMED ITS NARROWED ROWS AND NOT ITS FORCED ONES, so its median would
    # leave out every row that saved nothing. Only the sweep's timing subset is drawn.
    return {name: p for name, p in projects.items()
            if not p.get("recall_only") and (p.get("synthetic") or name in TIMING_SUBSET)}


def timing_caps_note(projects, pool):
    """What chart 2's title says about the timing cap, or a refusal when capped and uncapped
    files would be pooled without `pool`. A file without `timing_cap` timed every row."""
    caps = {p.get("timing_cap") for p in timing_projects(projects).values()}
    capped = sorted(c for c in caps if c is not None)
    if not capped:
        return ""
    if None not in caps and len(capped) == 1:
        return f" (first {capped[0]} rows per project)"
    if not pool:
        raise SystemExit(f"the timed files mix timing caps {sorted(caps, key=str)}; pass "
                         f"--pool-timing-caps to draw them together, and the title says so")
    return (f" (pools rows timed under caps {', '.join(map(str, capped))}"
            f"{' and uncapped files' if None in caps else ''})")


def one_job(row):
    """Whether a row's two arms ran in one job. A paired row's full arm is another row's
    (`full_ci`), and a ratio across two shared runners compares the runners, not the arms."""
    return row.get("full_ci", row.get("ci")) == row.get("ci")


def time_chart(projects, theme, note):
    t = THEMES[theme]
    points = []
    drawn = timing_projects(projects)
    # TIME ON A SHARED RUNNER IS QUOTED ONLY AS A RATIO OF TWO ARMS FROM ONE JOB. So a chart of CI
    # rows places each project by its suite's size in tests, never by seconds.
    on_ci = any(r.get("ci") for p in drawn.values() for r in p["rows"])
    across = 0
    for name, p in drawn.items():
        # The timed rows only: past the cap a narrowed row still runs a full arm, as its ground
        # truth, but it is not in the timing sample. A row from before the cap carries no `timed`.
        timed = [r for r in p["rows"] if r.get("timed") is not False
                 and r.get("full_seconds") and isinstance(r.get("selected_seconds"), (int, float))]
        across += sum(1 for r in timed if not one_job(r))
        saved = [1 - r["selected_seconds"] / r["full_seconds"] for r in timed if one_job(r)]
        size = (statistics.median(r["full_distinct"] for r in p["rows"]) if on_ci
                else p.get("baseline_seconds"))
        if saved and size:
            points.append((name, size, statistics.median(saved)))
    unit, axis = ((" tests", "suite size (tests, log)") if on_ci
                  else ("s", "suite length (uninstrumented, log)"))
    if across:
        note += f" ({across} row{'s' if across != 1 else ''} timed across two jobs left out)"
    points.sort()
    inside = [v for *_, v in points if v >= OFF_SCALE]
    off = [pt for pt in points if pt[2] < OFF_SCALE]
    y_lo = min([-0.25] + [math.floor(v / 0.25) * 0.25 for v in inside])
    y_hi = max([0.25] + [math.ceil(v / 0.25) * 0.25 for v in inside])
    width, left, right, top = 760, 70, 30, 56
    plot_w, plot_h = width - left - right, 240
    band_top = top + plot_h + 16 if off else None
    axis_y = band_top + 36 if off else top + plot_h
    height = axis_y + 50
    lo = min(math.log10(max(1.0, s)) for _, s, _ in points) if points else 0
    hi = max(math.log10(max(1.0, s)) for _, s, _ in points) if points else 1
    lo, hi = math.floor(lo), max(math.ceil(hi), math.floor(lo) + 1)

    def x(seconds):
        return left + (math.log10(max(1.0, seconds)) - lo) / (hi - lo) * plot_w

    def y(value):
        if value < OFF_SCALE:
            return band_top + 18
        return top + (y_hi - value) / (y_hi - y_lo) * plot_h

    body = []
    for decade in range(lo, hi + 1):
        seconds = 10 ** decade
        body.append(f"<line x1='{x(seconds):.1f}' x2='{x(seconds):.1f}' y1='{top}' "
                    f"y2='{axis_y}' stroke='{t['grid']}'/>"
                    f"<text x='{x(seconds):.1f}' y='{axis_y + 18}' {FONT} font-size='12' "
                    f"fill='{t['muted']}' text-anchor='middle'>{seconds:,}{unit}</text>")
    tick = y_lo
    while tick <= y_hi + 1e-9:
        body.append(f"<line x1='{left}' x2='{left + plot_w}' y1='{y(tick):.1f}' "
                    f"y2='{y(tick):.1f}' stroke='{t['muted'] if tick == 0 else t['grid']}' "
                    f"stroke-width='{1.5 if tick == 0 else 1}'/>"
                    f"<text x='{left - 8}' y='{y(tick) + 4:.1f}' {FONT} font-size='12' "
                    f"fill='{t['muted']}' text-anchor='end'>{_minus(f'{tick:+.0%}') if tick else '0%'}"
                    f"</text>")
        tick = round(tick + 0.25, 2)
    body.append(f"<text x='{left + plot_w}' y='{y(0) - 6:.1f}' {FONT} font-size='11' "
                f"fill='{t['muted']}' text-anchor='end'>break-even</text>")
    if off:
        # The break: two slanted strokes across the axis, then a band with no scale of its own.
        for dy in (-3, 3):
            mid = top + plot_h + 8 + dy
            body.append(f"<line x1='{left - 6}' x2='{left + 6}' y1='{mid + 3}' y2='{mid - 3}' "
                        f"stroke='{t['muted']}' stroke-width='1.5'/>")
        body.append(f"<rect x='{left}' y='{band_top}' width='{plot_w}' height='36' "
                    f"fill='none' stroke='{t['grid']}' stroke-dasharray='4 3'/>"
                    f"<text x='{left - 8}' y='{band_top + 22}' {FONT} font-size='12' "
                    f"fill='{t['muted']}' text-anchor='end'>{_esc(_minus(f'<{OFF_SCALE:+.0%}'))}"
                    f"</text>")
    for name, seconds, value in points:
        cx, cy = x(seconds), y(value)
        label = f"{name} " + _minus(f"{value:+.0%}") + (" (off scale)" if value < OFF_SCALE else "")
        # Near the right edge the label goes on the point's left, so it is never clipped.
        if cx + 8 + _width(label, 11) > width - 8:
            anchor, lx = "end", cx - 8
        else:
            anchor, lx = "start", cx + 8
        body.append(f"<circle cx='{cx:.1f}' cy='{cy:.1f}' r='5' fill='{t['series']}' "
                    f"stroke='{t['surface']}' stroke-width='2'><title>{_esc(name)}: suite "
                    f"{seconds:,.0f}{unit}, median {_minus(f'{value:+.0%}')} of the full run saved"
                    f"</title></circle>"
                    f"<text x='{lx:.1f}' y='{cy + 4:.1f}' {FONT} font-size='11' "
                    f"fill='{t['text']}' text-anchor='{anchor}'>{_esc(label)}</text>")
    body.append(f"<text x='{left + plot_w / 2}' y='{height - 10}' {FONT} font-size='12' "
                f"fill='{t['muted']}' text-anchor='middle'>{axis}</text>"
                f"<text x='{left}' y='{top - 16}' {FONT} font-size='12' fill='{t['muted']}'>"
                f"median share of the full run's time saved per change</text>")
    return _svg(width, height, theme,
                f"Time saved against suite {'size' if on_ci else 'length'}{note}", "".join(body))


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="directory to write the SVGs into")
    parser.add_argument("--arm", choices=("shared", "isolated"), default="shared")
    parser.add_argument("--sample", action="store_true", help="draw from SYNTHETIC rows")
    parser.add_argument("--commit", help="draw only files stamped with this plugin_commit")
    parser.add_argument("--pool-timing-caps", action="store_true",
                        help="draw files timed under different caps together; the title says so")
    parser.add_argument("--release", metavar="DIR",
                        help="draw from a published sweep's unpacked release asset")
    parser.add_argument("--headline", action="store_true", help="also print the recall headline")
    parser.add_argument("results", nargs="*", help="results files (default: bench/results)")
    args = parser.parse_args(argv)
    if args.sample:
        projects, skipped, note = sample(), {}, " (SYNTHETIC SAMPLE, not a measurement)"
    elif args.release:
        projects, skipped = load_release(args.release)
        note = ""
    else:
        projects, skipped = load(args.arm, args.results, args.commit)
        note = "" if args.arm == "shared" else " (isolated capture)"
        if args.commit:
            note += f" at {args.commit}"
    if not projects:
        print("no results rows to draw", file=sys.stderr)
        return 1
    os.makedirs(args.out, exist_ok=True)
    timing_note = (timing_caps_note(projects, args.pool_timing_caps) if args.arm == "shared"
                   else "")
    for theme in THEMES:
        suffix = "" if theme == "light" else "-dark"
        # The isolated recording has no timing subset: its selected arm ran under raised forks and
        # its full arm is the shared one's, so no time-saved chart is drawn for it.
        charts = [("selected-share", share_chart)]
        if args.arm == "shared":
            charts.append(("time-saved", time_chart))
        for name, render in charts:
            path = os.path.join(args.out, f"{name}{suffix}.svg")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(render(projects, theme,
                                    note + (timing_note if name == "time-saved" else "")))
            print(path)
    drawn = [row for p in projects.values() for row in p["rows"]]
    mix = Counter(row.get("ground_truth") or "full" for row in drawn)
    if args.headline:
        print(headline(projects, read_codebases()))
    print(f"{len(projects)} projects, {len(drawn)} rows drawn, {left_out(skipped)}; ground truth: "
          + ", ".join(f"{count} {name}" for name, count in sorted(mix.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
