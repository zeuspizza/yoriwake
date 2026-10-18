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

For now reports are answered by the maintainer alone, with no backup. A second person with access
to private advisories is named before 1.0.

## Supported versions

The latest release. Fixes ship as a patch on the latest minor version; upgrade to get them.

## Scope

- The agent, which runs as a `-javaagent` inside your test JVM.
- The plugin, which runs inside your Gradle build.
- How the coverage map is read back, including a map restored from a CI cache that a pull request
  could have written.
- The GitHub Action, [zeuspizza/yoriwake-action](https://github.com/zeuspizza/yoriwake-action).

## Verifying a release

From 0.3.0, each release is built and tested once by `release.yml` on the version's tag, and those
same files are attested, signed and uploaded. You can check, for a version `<version>`:

1. **The jar is the one the release built.** Download it from the Plugin Portal, and `SHA256SUMS`
   from the [GitHub release](https://github.com/zeuspizza/yoriwake/releases):

   ```bash
   v=<version>
   curl -fsSLO "https://plugins.gradle.org/m2/io/github/zeuspizza/yoriwake-gradle-plugin/$v/yoriwake-gradle-plugin-$v.jar"
   curl -fsSLO "https://github.com/zeuspizza/yoriwake/releases/download/v$v/SHA256SUMS"
   grep "/yoriwake-gradle-plugin-$v.jar\$" SHA256SUMS
   shasum -a 256 "yoriwake-gradle-plugin-$v.jar"
   ```

   The two digests are the same.

2. **It was built by this repository's release workflow, from that tag** (GitHub CLI 2.49 or newer):

   ```bash
   gh attestation verify "yoriwake-gradle-plugin-$v.jar" -R zeuspizza/yoriwake \
     --signer-workflow zeuspizza/yoriwake/.github/workflows/release.yml \
     --source-ref "refs/tags/v$v" --deny-self-hosted-runners
   ```

   It prints `✓ Verification succeeded!`. Without `--signer-workflow` and `--source-ref` it would
   accept an attestation from any workflow of this repository, on any ref.

3. **What it bundles.** The SBOM on the GitHub release lists every library in the jar, the agent
   nested in it, and the package each was relocated to. It is one of the files step 2's attestation
   covers, so check the copy you read against it:

   ```bash
   curl -fsSLO "https://github.com/zeuspizza/yoriwake/releases/download/v$v/yoriwake-gradle-plugin-$v.cdx.json"
   gh attestation verify "yoriwake-gradle-plugin-$v.cdx.json" -R zeuspizza/yoriwake \
     --signer-workflow zeuspizza/yoriwake/.github/workflows/release.yml \
     --source-ref "refs/tags/v$v" --deny-self-hosted-runners
   ```

   The same SBOM is also attested against the jar itself, as a CycloneDX predicate
   (`--predicate-type https://cyclonedx.org/bom` on the jar prints it).

4. **The agent your tests run.** It is inside the jar; step 2 for the plugin jar covers it, and it
   can be checked on its own:

   ```bash
   unzip -p "yoriwake-gradle-plugin-$v.jar" yoriwake-agent.jar > yoriwake-agent.jar
   grep ' yoriwake-agent.jar$' SHA256SUMS
   shasum -a 256 yoriwake-agent.jar
   ```

5. **It is signed by the project's release key**, fingerprint
   `81DB8AB88E9C8E4968B07F75EA16A721BE2F2196`, in [RELEASE-KEY.asc](RELEASE-KEY.asc) and on
   keys.openpgp.org and keyserver.ubuntu.com:

   ```bash
   curl -fsSLO "https://plugins.gradle.org/m2/io/github/zeuspizza/yoriwake-gradle-plugin/$v/yoriwake-gradle-plugin-$v.jar.asc"
   curl -fsSLO https://raw.githubusercontent.com/zeuspizza/yoriwake/main/RELEASE-KEY.asc
   gpg --import RELEASE-KEY.asc
   gpg --verify "yoriwake-gradle-plugin-$v.jar.asc" "yoriwake-gradle-plugin-$v.jar"
   ```

   `gpg` reports a good signature from `81DB8AB88E9C8E4968B07F75EA16A721BE2F2196` (a warning that
   the key is not certified with a trusted signature is expected).

**In your build:** Gradle's [dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
checks the signature on every resolution when `gradle/verification-metadata.xml` trusts the key for
this group. Gradle looks keys up on keys.openpgp.org:

```xml
<trusted-keys>
   <trusted-key id="81DB8AB88E9C8E4968B07F75EA16A721BE2F2196" group="io.github.zeuspizza"/>
</trusted-keys>
```

The plugin marker (`io.github.zeuspizza.yoriwake.gradle.plugin`) is not signed on the Portal, for
any plugin; verification records it by checksum.

Nobody outside the project has followed these steps yet.
