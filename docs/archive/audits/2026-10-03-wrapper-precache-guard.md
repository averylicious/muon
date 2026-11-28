# CI wrapper verification before cache restoration — 2026-10-03

Baseline main `c357694c64fde20762e7a88de44c5ab03c8feecb`. GPT-6 / Codex desktop, effort not reported: implementation and source/self-review. Continues W1 in [the wrapper trust report](2026-10-03-wrapper-trust.md), not a new compromise claim or a full supply-chain clearance.

## Why and what changes

The pinned setup-gradle action restores Gradle User Home before validating the wrapper. It accepts hashes from `.setup-gradle/valid-wrappers.json`, which that cache includes. A fresh action runner is therefore not an independent checksum basis for a restored allowlist. Repository writers and code already running in builds remain trusted; a viable malicious cache path was not demonstrated.

`tools/verify_gradle_wrapper.py` now compares the tracked wrapper JAR to an explicit reviewed checksum before `setup-gradle` in Android APKs, Baseline Profile tools and Dependency inventory workflows. It reads no cache or environment-provided allowlist, requires a regular non-symlink JAR and fails the step/job on mismatch or read error. The Android step follows the existing full-build selector, so docs-only CI still skips SDK/Gradle/signing. Existing action validation stays enabled as a second check. Auxiliary path filters include changes to the guard/tests.

Trust basis: the coordinator fetched the official [Gradle8.13 wrapper checksum](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256) on October3 and independently hashed the current tracked JAR. Both equal `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`. This is a narrowly reviewed wrapper pin, not generated verification metadata for all cached artifacts. A deliberate wrapper replacement requires reviewing/updating this pin; Gradle distribution upgrades that do not replace the JAR are still governed by their separately reviewed distribution checksum.

## Verification and limits

Python tests exercise accepted content, modification, missing file, symlink refusal and workflow placement/filter coverage. All three invoking workflows must reach the guard before cache restore and Gradle; signing remains later. Full latest-head CI is pending at drafting; no local Gradle or phone testing.

This does not authenticate cached unpacked distributions, Maven artifacts, plugins, JDK/SDK, runners, or the full action bundles; dependency locking/verification and cache provenance stay open. A trusted workflow editor can change the pin or guard, and later code with runner filesystem access can mutate the wrapper. No claim of a sandbox or elimination of all time-of-check/use attacks. Device QA is not needed because app behavior/identity is unchanged; CI/artifact verification is required. Final head, workflow results and self-review belong on the PR and next checkpoint.
