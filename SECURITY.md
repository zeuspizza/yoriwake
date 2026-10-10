# Security policy

## Reporting

Report a vulnerability privately through GitHub's private vulnerability reporting for
`zeuspizza/yoriwake`: the repository's **Security** tab, then **Report a vulnerability**. Please do
not open a public issue.

## What happens next

yoriwake is an actively maintained free-time project. Reports are read and fixed as soon as the
maintainer can, a test that should have run and did not first of all, often within days; no
response time and no support window are promised. If a report shows a test being skipped that
should have run, the advisory names the safe step (run without `-Pyoriwake.select`) as soon as it
is confirmed. How fixes are released is in [RELEASING.md](RELEASING.md).

## Supported versions

The latest release. Fixes ship as a patch on the latest minor version; upgrade to get them.

## Scope

- The agent, which runs as a `-javaagent` inside your test JVM.
- The plugin, which runs inside your Gradle build.
- How the coverage map is read back, including a map restored from a CI cache that a pull request
  could have written.
- The GitHub Action, [zeuspizza/yoriwake-action](https://github.com/zeuspizza/yoriwake-action).
