#!/bin/sh
# The release's signing and upload tasks, which otherwise run for the first time in a real release:
# a throwaway key signs every file of a local release, signing without a key fails, and an unsigned
# upload is refused before anything reaches the Plugin Portal.
set -eu
version=0.0.0-signing-check
repo=plugin/build/local-repository
./gradlew :plugin:publishAllPublicationsToBuildLocalRepository -PreleaseVersion=$version --console=plain
GNUPGHOME=$(mktemp -d)
export GNUPGHOME
gpg --batch --pinentry-mode loopback --passphrase check --quick-gen-key 'throwaway <check@example.invalid>' ed25519 sign 1d
key=$(gpg --batch --pinentry-mode loopback --passphrase check --armor --export-secret-keys)

if ./gradlew :plugin:signReleaseRepository -PreleaseVersion=$version -Pyoriwake.sign --console=plain > "$GNUPGHOME/no-key.log" 2>&1; then
    echo "signing without a key succeeded"; exit 1
fi
grep -q "no configured signatory" "$GNUPGHOME/no-key.log"

ORG_GRADLE_PROJECT_signingKey="$key" ORG_GRADLE_PROJECT_signingPassword=check \
    ./gradlew :plugin:signReleaseRepository -PreleaseVersion=$version -Pyoriwake.sign --console=plain
missing=$(find $repo -type f ! -name '*.asc' ! -name '*.md5' ! -name '*.sha*' ! -name 'maven-metadata.xml*' |
    while read -r file; do gpg --verify "$file.asc" "$file" 2>/dev/null || echo "$file"; done)
if [ -n "$missing" ]; then echo "not signed, or not by the throwaway key: $missing"; exit 1; fi

if ./gradlew :plugin:publishReleaseRepository -x :plugin:signReleaseRepository -PreleaseVersion=$version \
        --console=plain > "$GNUPGHOME/unsigned.log" 2>&1; then
    echo "an unsigned upload ran"; exit 1
fi
grep -q "a release is uploaded signed" "$GNUPGHOME/unsigned.log"
echo "signing checked: $(find $repo -name '*.asc' | wc -l) signatures, no-key and unsigned-upload refusals"
