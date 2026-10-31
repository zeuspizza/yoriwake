# Accessibility

yoriwake should be usable by anyone who builds and tests Java or Kotlin code, whatever they read it
with.

## What you use yoriwake through

- **Your build's output.** The Gradle plugin and the Maven plugin print plain lines of text through
  the build's own log. No colour or symbol alone carries a meaning: what a line says is in
  its words, so it reads the same in a screen reader, a CI log or a file.
- **The documentation.** Markdown in this repository, read on GitHub or in any editor.
- **Two charts** in [docs/benchmarks.md](docs/benchmarks.md), each with alt text, a dark-mode
  variant and a caption saying what it shows. The second chart's figures are also in the table
  below it.

The logo and the badges in the [README](README.md) have alt text.

## Known barriers

- The first chart in [docs/benchmarks.md](docs/benchmarks.md) places one dot per change; those
  per-change values are not in a text table. Its caption gives the counts and the median.

## Reporting a barrier

Open a [blank issue](https://github.com/zeuspizza/yoriwake/issues/new) saying what you were trying
to do, what you used to do it, and where it failed. Barriers are fixed as soon as the maintainer
can; no response time is promised.
