# Releasing QueryFence

Only the maintainer releases, by hand. Nothing in this repository publishes on its own: the release
workflow runs when **you** push a tag, and it uploads to Maven Central with `autoPublish=false`, so
the last step is still a button you press.

Never ask an AI assistant to run `deploy`, push a tag, or touch the GPG key or the Central tokens.
Those steps are yours alone, and the rest of this page assumes you are at the keyboard.

## Before you start: the one-time setup

You need these once, not per release.

### 1. A GPG key that Maven Central trusts

```bash
gpg --full-generate-key          # RSA 4096, no expiry or a long one, your release e-mail
gpg --list-secret-keys --keyid-format=long
gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
```

Central checks that the public key is on a keyserver. Export the private key for CI:

```bash
gpg --armor --export-secret-keys <YOUR_KEY_ID> | pbcopy
```

### 2. A Central Portal account and token

Register the `com.steelreed` namespace at <https://central.sonatype.com/> (Namespaces → Add
namespace). Central verifies a domain namespace through DNS: add the TXT record it shows to
`steelreed.com`, wait for the namespace to turn verified, then generate a user token (Account →
Generate User Token).

### 3. GitHub secrets

In **Settings → Secrets and variables → Actions**, add exactly four:

| Secret | What it holds |
|---|---|
| `GPG_PRIVATE_KEY` | the armored private key from step 1 |
| `GPG_PASSPHRASE` | the passphrase of that key |
| `CENTRAL_USERNAME` | the Central Portal token username |
| `CENTRAL_PASSWORD` | the Central Portal token password |

Nothing else needs them, and nothing prints them. If you ever paste one into a chat, revoke it.

## Releasing 0.1.0

### 1. Check the tree is ready

```bash
./mvnw -B clean verify
./mvnw -B -pl queryfence-integration-tests -am -Pintegration verify   # needs Docker
```

Dry-run the release build without signing anything:

```bash
./mvnw -B -Prelease -Dgpg.skip=true verify
```

Every published module must produce three jars:

```bash
ls queryfence-*/target/*.jar
```

`queryfence-core`, `queryfence-jdbc`, `queryfence-report`, `queryfence-junit5` and
`queryfence-spring-test` each need `<module>-<version>.jar`, `-sources.jar` and `-javadoc.jar`.
`queryfence-bom` is a POM and has none. `queryfence-integration-tests` is never published.

### 2. Check the paperwork

- `CHANGELOG.md` has a `[0.1.0]` section with today's date, and `[Unreleased]` is empty.
- `README.md` and `docs/getting-started.md` show the version you are about to release.
- The documentation site builds: `mkdocs build --strict` (see `docs/requirements.txt`).

### 3. Sign locally once, to be sure

This is the only step that touches your key, and it stays on your machine:

```bash
./mvnw -B -Prelease verify          # prompts for the GPG passphrase
ls queryfence-core/target/*.asc     # signatures next to every artifact
```

If `gpg` cannot find a key, fix that before tagging. A failing signature in CI wastes a tag.

### 4. Tag and push

The tag is what starts the release; the workflow derives the version from it
(`v0.1.0` → `0.1.0`).

```bash
git checkout main && git pull
git tag -a v0.1.0 -m "QueryFence 0.1.0"
git push origin v0.1.0
```

### 5. Watch the workflow

**Actions → Release**. It sets the version from the tag, builds, signs with the key from the
secrets, uploads to Central and creates the GitHub release. It does **not** publish: the bundle
waits for you.

### 6. Publish the bundle

Open <https://central.sonatype.com/publishing/deployments>, check the deployment
(five artifacts, each with sources, javadoc and `.asc`), then press **Publish**. Artifacts appear on
Maven Central within about ten minutes, and in the search index within a few hours.

## After publishing

### Verify it from the outside

In an empty directory, with no local build:

```bash
mvn dependency:get -Dartifact=com.steelreed:queryfence-spring-test:0.1.0
```

Then the honest test — run an example against the released artifact:

```bash
cd examples/postgres-jpa
docker compose up -d
../../mvnw verify -Dqueryfence.version=0.1.0
```

It must fail with a QueryFence violation, not with a resolution error. `docker compose down -v`
when you are done.

### Tidy up

- Close the milestone, if you used one.
- Move `examples/*/pom.xml` to `<queryfence.version>0.1.0</queryfence.version>` only if you want the
  examples to build against the released artifact; leaving them on `-SNAPSHOT` keeps CI checking the
  working tree, which is usually what you want.
- Open the next `[Unreleased]` section in `CHANGELOG.md`.

### If something is wrong

You cannot replace a published version on Maven Central. Release `0.1.1`. Before publishing, a
deployment can still be dropped from the Central Portal, which is the last moment anything is
reversible — one more reason `autoPublish` stays `false`.
