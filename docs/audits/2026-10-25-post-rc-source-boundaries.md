# Post-RC source-boundary checks — 2026-10-25

GPT-6 / Codex desktop; exact variant/effort not reported. Bounded source/API and repository-metadata assessment, author self-check. Main inspected at `dd8782a0426437093a99ea3f7205878b6af775bc`; fourteen-input runtime integration .1492 is `d90b41b754332989b2a88786f5f1afa3baef77a8`. It does not change installed frozen RC .1420. No phone, music-file, host/network or repository-setting change during these checks. This report is supporting evidence, not a new release gate or whole-app security/performance certification.

## Playback ordering and legacy connections

Pinned Google Maven session1.11.0 sources SHA256 `9fa36c24ead02c89325d5b89d5322f2781b1b50511d1b432b4b033ab3c38e715`:

- `ConnectedControllersManager.flushCommandQueue` waits on the current asynchronous command before draining the next command **for that controller**. Muon's saved-list preparation also revokes its revision token on replacement. A suspected late saved admission overwriting a newer live request was not established in this inspected route. Different controllers/platform ordering are not globally cleared by this observation.
- `MediaSessionServiceLegacyStub.onGetRoot` and `MediaSessionLegacyStub` use `MediaSessionImpl.onConnectOnHandler`; the default deprecated callback marker delegates to `onConnectAsync`. No separate ordinary legacy-callback bypass was found. Manifest playback service remains non-exported.
- An important exception is the System UI bridge: once the in-process notification controller connects, its grant becomes the platform session's grant; System UI receives that result and callbacks can be resolved through the notification controller. Muon's own-UID branch grants more edit/configuration authority than that controller needs. [#711](issues/711.md)/[#712](https://github.com/averylicious/muon/pull/712) proposes a separate least-privilege correction; tests/acceptance remain at the exact PR head.

The last item is a policy-grant finding, **not** a demonstrated package-name-spoofing exploit or proof of untrusted cross-app token access. Android package visibility, calling-UID propagation and physical System UI behavior were not tested. Do not revive the previously withdrawn spoofing claim. Current [#708](issues/708.md) separately addresses process-wide saved-worker admission across callback owners, not every Binder request or session thread.

## HTTP cancellation and UI projection

`NetworkRequest.readCancellable` keeps the cancellation handler attached through synchronous execute, body consumption and Response.use closure. The inspected API/artwork/LAN-probe consumers return ordinary bytes/text/scalars, not an escaping open Response. The finite-metadata client has its whole-call deadline; audio/download streams deliberately use the separate ongoing-stream timeout policy. One finite-request deadline is not a promise that an entire multi-playlist library refresh finishes in that time. No new missing-cancellation route or #671 cause was established.

`MuonApp` constructs `LibrarySnapshot(model.allTracks)` inside remember keyed by playlist/track snapshots. Artwork identities are likewise remembered per library/origin. A suspected whole-library rebuild on every playback-position update is therefore not supported by this source. Snapshot creation can still cost work during an actual reload; this is not measured frame/jank clearance. Worker-projection #639/#684 has separate acceptance.

## CI cancellation/publication and server-side controls

GitHub documents workflow/job cancellation and one-group pending behavior in its [concurrency reference](https://docs.github.com/en/actions/concepts/workflows-and-actions/concurrency) and [cancellation reference](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-cancellation). Muon's branch workflow can cancel an older run, including an older main run. The existing `ci_scope.classify` main path already compares against the newest complete published Canary as well as before/head. A docs-only follow-up that cancels unpublished code therefore remains eligible for a full build; the concern is already handled, so no concurrency rule was changed. This preserves recovery of current code, not publication of every intermediate commit. The feature-branch inherited-main exception also requires complete matching Canary evidence.

Live repository API metadata readback:

- Public visibility; strict main checks are `Build, test and sign` and `Branch direction`, with administrator enforcement, no force-push/deletion.
- Fork approval policy `all_external_contributors`.
- Secret scanning and push protection enabled; open-alert metadata query returned an empty list. No secret values printed. Non-provider patterns/validity checks and automatic Dependabot security updates remain disabled; that is not a claim that dependency risk is absent or permission to change the user's update policy.

The separate [October25 credential rescan](2026-10-25-credential-rescan.md) subsequently repeated reachable history/text-blob checks with a qualified snapshot. Zero alerts and zero confirmed leaks in that scan are not exhaustive absence of credentials in all refs/artifacts. Dependency/publisher/tool provenance retains its [explicit disposition](2026-10-07-build-trust-disposition.md). No credential rotation, permission expansion or signing-material access occurred.

## Additional checked entry point

`MainActivity` does handle a fixed storage-review intent; it does not ignore all extras. `StorageReviewEntry` requires the canonical private capability and fixed action, compares the capability in constant time and does not create it during validation. It accepts no arbitrary path/configuration mutation. This source check is not cross-app/device execution proof, and does not certify every activity route.

The pinned ExoPlayerImpl.maskTimelineAndPosition also clears TrackGroupArray/selection/static metadata when the playing period changes (and on an empty timeline), retaining them for same-period backward seeks. That supports reading current selected tracks in #695 rather than preserving a previous listener-order cache. It does not prove every masking/renderer/device transition or replace listening acceptance. Settings' ReplayGain instance changes the switch; the service publishes its own current loudness state, so a separate UI median-cache correctness bug was not established in the inspected path.

## Backup policy and app-authored logging

At the inspected app tree, the manifest disables backup/fullBackupContent and names data_extraction_rules.xml. The XML excludes all listed credential/device-protected app-storage domains in cloud backup and device transfer; the existing CI privacy checks exercise that declaration. This is source/policy evidence, not verification that every OEM transfer tool/root backup honors it, nor a statement that external surviving SD files disappear on reset.

The inspected app-authored Log calls are diagnostic/timing observers gated by DEBUG, explicit STORAGE_TIMINGS or a debuggable ApplicationInfo. Their payloads use fixed enums, booleans and numeric counters/durations, not song/server/path/exception text. Timing observers have fixed operation/event budgets; the journal mode diagnostic emits once per observer, not a claimed universal process budget. No new application logging defect was identified. Library/framework/system logs and a real device logcat were not audited by this check; there was no device access.

## Old NSD source qualification

Official AOSP `android-9.0.0_r1` [NsdService](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/services/core/java/com/android/server/NsdService.java) SHA256 `bcc8cf054ad6e56fa8442ae24ac16af24a796159d1f59570329175b2e2d9bc6f` refuses another resolve with FAILURE_ALREADY_ACTIVE while that client's mResolvedService is non-null. Completion/failure clears it; disconnect cleanup cancels registered requests. Its [NsdManager](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/net/nsd/NsdManager.java) SHA256 `00832e7e60baf93a3496bc9e27401f8915b3d0a70c749384faf1e51d8980045c` removes successful/failed ResolveListeners before callback, but provides no public cancellation in that old API.

Thus repeated Muon logical timeouts do not demonstrate an unbounded native-resolve queue on this inspected API28 implementation: a later request is refused while the earlier one remains active. A stuck original may still hold its listener/client slot until callback/disconnect; the app's5s timer does not cancel it. This narrows the earlier resource question without establishing later Android/OEM behavior, native-daemon timeout guarantees or a reproduced leak/#671 cause. No blind fallback rewrite, platform-setting change or phone test was performed.

## Narrow remaining source questions

- Container-specific Vorbis/Ogg allocation admission remains [#714](issues/714.md). The safe raw-parser characterization is separate from production guards; Loader catches OutOfMemoryError, so no demonstrated app crash/OOM or #671 diagnosis follows.
- ReplayGain deliberately preserves remembered history. Its SharedPreferences median/cache is not a full persistent-history memory redesign; no phone OOM or measured hot-path cost is established. Keep this distinct from #695's audio-identity correction, and do not delete history just to impose a count.
- `ArtworkDiskCache.write` on main lacks temporary cleanup if writing/closing fails after creation. Root included a small finally cleanup (`b7d83ba7a8d32b3ce2bf604e581c271e03aa4cb3`) in combined `d90b41b754332989b2a88786f5f1afa3baef77a8`, whose .1492 compile and existing tests passed. This attempts deletion of only its derived-art temporary; no saved audio, original cover or historical #646 state is touched. Existing cache tests remain; no partial-write/ENOSPC injection or guarantee that a failing filesystem permits deletion.
- Discovery's old resolve fallback now has the API28 source qualification above. Its platform single-client slot narrows the unbounded-queue hypothesis for that version; later Android/OEM cancellation/retention remains unverified. Logical timeout alone is not physical cancellation. No NSD/network setting was changed.

Continue from maintained [issue state](issues/README.md) and the [current continuation](../handoffs/2026-10-25-post-rc-continuation.md). Source questions are not newly invented frozen-RC blockers.

## Development version-label check

The .1511 branch release-package artifact reports0.1.0 despite published Stable0.2.0. This matches the explicit [development-version convention](../release-naming.md): workflow non-tag builds use an independent base and increasing run-number version code; Stable tags override the display version. Both main205 and alphaEE keep that development fallback. This inspection did not establish a signing/update-order failure, so no unrequested version/channel change was made. Actual APK identity/source/hash checks remain separate from naming policy.


## Additional bounded source checks — 2026-10-28

At candidate74d532e2, artwork trimming already uses DirectoryStream plus at most8,193 retained candidates, rather than listFiles/sort of all names. This does not certify filesystem-failure cleanup or all cache memory. The track-list parser deliberately preserves JSONObject coercions; its cancellation checkpoints sit outside deferred per-record projection failures. No new coercion/cancellation defect was established in those inspected paths.

A focused scan of interpolated SQLite raw-query/statement sites checked DiskOwnershipCensus, LegacyResourceProjection/Retirement/TransitionOwner, PartitionNativeAdmission/CompletionIndex/ContentMetadata, ReadOnlyLegacySavedAudio and selected migration/catalog/Delete journal bindings. Dynamic tables use fixed prefixes plus Long.toHexString UIDs or the validated64-character ASCII alphanumeric/underscore index name; query projections, state predicates and limits are internal constants/closed choices. External key/name values use selection arguments or bindBlob/bindLong. No injection found in these selected paths; this was source inspection, not every query/native SQLite behavior, corruption fuzzing or a device test. No new tests were added merely to assert SQL strings.

At candidate4721dd91, LibraryLoadBudget counts each playlist occurrence rather than unique IDs, charges aggregate encoded bytes and artist credits before publication, and charges fallback projection too. Failed batches do not commit counters. No duplicate-ID budget bypass was established; this does not cap every transient old/new snapshot or prove device heap usage. Lyrics use a512KiB response,64Ki UTF-16-unit/2,048-line display limit before selectable text; cancelled requests rethrow cancellation. These narrow paths did not produce a new finding.

The same source snapshot keeps live artwork on the app-built Tauon URL, saved artwork on canonical local entry handles, and malformed saved-art handles fail without HTTP fallback. Pinned1.11.0 extractor metadata helpers contain no setArtworkUri call; no stream-tag remote-art-URL injection route was found in those helpers. Embedded image bytes use the separately bounded notification decoder. This is not every player/controller/library/HTTP path or a runtime network/privacy guarantee. No host or phone network test was performed.
