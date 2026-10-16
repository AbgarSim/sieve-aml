# Releasing Sieve

Sieve's library modules are published to Maven Central under the group id `org.sieveaml`:

| Artifact | What it is |
| --- | --- |
| `org.sieveaml:sieve` | Parent POM (dependency versions) |
| `org.sieveaml:sieve-core` | Domain model, `MatchEngine` and `EntityIndex` interfaces |
| `org.sieveaml:sieve-match` | Exact, fuzzy, phonetic and token matching engines |
| `org.sieveaml:sieve-ingest` | Sanctions and risk list loaders |
| `org.sieveaml:sieve-risk` | Behavioural risk scoring |
| `org.sieveaml:sieve-address` | Address normalisation (libpostal is optional) |

The servers, the CLI, the Beam pipeline and the benchmarks are applications. They are built and
tested with every release but not published to Maven Central.

`sieve-ingest` declares Playwright as an optional dependency. The Israel (NBCTF) and Turkey (MASAK)
loaders need it to drive a headless browser; add `com.microsoft.playwright:playwright` to your own
build if you use them.

## Versions

The version on `main` is always the next release with `-SNAPSHOT`, for example `0.1.0-SNAPSHOT`.
Releases follow [semantic versioning](https://semver.org/). Before 1.0.0, a minor version
(0.2.0) may change the public API.

## One-time setup

1. Create an account at <https://central.sonatype.com> and add the namespace `org.sieveaml`.
2. Add the TXT record the Portal shows to the DNS of `sieveaml.org` and press Verify.
3. Create a GPG key, then publish its public part:
   `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`.
4. In the Portal, generate a user token (Account > Generate User Token).
5. Add these repository secrets in GitHub (Settings > Secrets and variables > Actions):
   - `CENTRAL_USERNAME` and `CENTRAL_TOKEN`: the two parts of the user token
   - `GPG_PRIVATE_KEY`: the output of `gpg --armor --export-secret-keys <KEY_ID>`
   - `GPG_PASSPHRASE`: the key's passphrase

## Making a release

1. Make sure CI is green on `main` and `CHANGELOG.md` describes the release.
2. Tag the commit and push the tag:
   ```sh
   git tag v0.1.0
   git push origin v0.1.0
   ```
3. The Release workflow sets the version from the tag, runs the tests, builds the sources and
   javadoc jars, signs everything and uploads it to the Central Portal.
4. Open <https://central.sonatype.com/publishing>, check the deployment and press Publish.
   Artifacts appear on Maven Central within about half an hour. A published version can never
   be changed or removed.
5. Open a pull request that moves `main` to the next snapshot:
   ```sh
   mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules=true -DgenerateBackupPoms=false
   ```

To check a release build locally without signing or uploading:

```sh
mvn -Prelease -Dgpg.skip -DskipTests verify
```
