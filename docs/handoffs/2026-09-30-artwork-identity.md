# Artwork identity checkpoint — 2026-09-30

## Ownership and destination

GPT-6, Codex desktop, effort not reported (user nickname Sol), owns `codex/artwork-identity` in the isolated main-audit worktree. Based on main `4fd3af9373bfa232e637c06e9705dae49b9b8fe1`. No experimental checkout, Claude session or phone was used. The user requires phone-QA-dependent PRs to remain open. No Stable release or artifact deletion is authorized.

## Finding and fix

[#207](https://github.com/averylicious/muon/issues/207): memory covers and Compose effect/state keys ignored the title/artist/album identity already used on disk. A same-URL identity change could retain the previous song's cover and player colour. Memory keys now include the captured identity and size. The registry is observable Compose state; cover and colour effects observe its request identity. Visible loaded state resets when that identity changes, and colour extraction runs again without reusing that URL's previous song colour.

A short synchronized publication guard rejects an old fetch after the library identity changes. Memory uses the captured identity; late disk writes retain their original identity and cannot be stored as the replacement song. Disk IO stays outside the registry lock. Existing disk names, response/decode limits, cache budget, settling behavior and downloaded-cover fallback are retained. This does not solve changed cover content with unchanged title/artist/album, or redesign downloaded audio/art identity.

Five JVM tests cover identity/size/origin separation, late publication, actual snapshot observer invalidation, equal-snapshot stability, and unknown/known/disconnected identity transitions. Snapshot observer APIs were checked in the runtime source version resolved from main's Compose BOM on Google's Maven; versions remain in Gradle. No Compose UI/device test or performance measurement is claimed. No new dependency.

## Verification and QA

Local whitespace/branch direction preflight; no local Android build. Actions is the first compile. Exact final-head results and artifact availability will be on the PR and #181. Artifact uploads are currently quota-blocked; do not claim a downloadable APK or a successful overall run from successful compilation alone.

Phone QA pending: change title/cover or reuse an undownloaded Tauon track number, refresh without restarting Muon, and check row/detail/player artwork and player colours; repeat while a cover request is in flight. Normal scroll/growing artwork and offline downloaded covers must still work. Same metadata with changed cover is an existing cache-age limitation, not an acceptance check this fix promises.

## Parked work and resume

- #205 head `b3e86da810810d51c1bf91769642119a2e810971`: run 364 passed with APK; private-service compatibility QA pending. Leave open.
- #206 head `d85c602eb2bc27b7852e14f75c4e229c73cf1c63`: run 366 built/tested/linted/verified identities, failed artifact quota; no APK. Deadline/cancellation changes are not included here.
- #209 head `0cfdf05b3abd6886b8ebbcaeaeaa1b4da925ea47`: library ownership fix, run 367 pending when this checkpoint was written. Consult PR for newer results; its [handoff](2026-09-30-library-load-ownership.md) records scope and QA.
- Main Canary .363 remains the last verified publication; these branches are not Obtainium releases and do not include the user's experimental UI changes.
- Artifact cleanup approval remains pending; generic continuation does not authorize deletion. Keep storage failure separate from test failure. Do not repeatedly rerun unchanged heads while quota remains unchanged.
- Next deeper areas: #179 removable-storage ownership/data preservation, aggregate resource bounds, playback lifecycle, and actual resolved dependency/advisory/integrity review. First-pass dependency inspection found pinned Action SHAs and wrapper checksum, repository restrictions and separate read/write CI jobs; no Gradle dependency verification metadata or lockfile was found. This is an integrity gap/question, not evidence of compromise or an advisory verdict.

Before resuming verify heads/CI/ownership, including earlier pending runs, then finish this bounded PR and record its exact head. Forward integration remains a separate owner-landed experimental PR. Preserve QA and upload gates before another slice.
