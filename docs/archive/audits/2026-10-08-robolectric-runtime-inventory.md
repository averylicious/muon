# Isolated Robolectric runtime inventory

Main base f78874aa0e60d8a7072262ad725b125019537ffd. GPT-6 / Codex desktop (exact variant/effort not reported): workflow/parser author and source self-review. No independent review, phone work, app change, dependency upgrade or Stable publication.

## Gap and bounded change

Pinned Robolectric4.16.1 MavenDependencyResolver loads framework jars at test runtime, outside Gradle dependency verification. Published resolver sources SHA256 `3c121afd711770c9af9fd5994659cedafd795a32ab41e5260112385f018b156c`: `maven.repo.local` overrides other cache selection; MavenRoboSettings uses `robolectric.dependency.repo.url`; existing local jars return without rehash; cold fetch compares POM/jar against same-repository SHA512 and commits all four files.

Dispatch-only `runtime_inventory`, default false, executes both actual unit-test variants with a fresh empty per-run Maven directory, explicit HTTPS repo1 Maven Central and no task/build-cache reuse. An audit-only init script sets properties on Test JVMs. No ordinary build imports it; normal runner repositories remain untouched. Only strictly validated public coordinates, filenames, byte counts and hashes go into the JSON artifact. Unknown paths/files, symlinks, missing/mismatched companions and excessive counts/sizes fail rather than leaking source directories. No jars/POMs, local paths, Gradle-home/SDK files or private logs are uploaded. The job has read-only contents permission, no signing secrets or APK/release publication.

This records cold-run artifact observations and checked repository-checksum agreement, NOT independent publisher authentication. It does not eliminate runtime trust in downloaded code, establish all native/executable provenance, enforce a hash policy, prove an ordinary warm-cache run safe, or resolve the whole build-trust release gate. No poisoned artifact/cache or exploit demonstrated. A configured URL is not proof of absence of HTTP redirects; no final network endpoint claim.

## Validation and next task

Local parser tests cover safe public receipt, changed bytes, missing companions, unknown private files, symlink files/directories, count/individual/aggregate byte bounds, empty repository, invalid commit and malformed checksum text. No local Gradle/Android run. Exact-head normal CI and the manual unsigned runtime receipt are pending; PR receipts must supersede this source snapshot before treating observations as measured evidence.

After the manual job, inspect the exact receipt and actual test reports; retain public receipt bytes if useful for future comparison. Review publisher/repository admission and additional executable/cache trust separately. This independent CI-only slice may merge at verified latest head under standing authorization; application acceptance #381 and #230/#253 remain open.
