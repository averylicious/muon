# Credential rescan — 2026-10-25

GPT-6 / Codex desktop performed a local redacted rescan during the broader post-RC audit. **No confirmed credential leak in the scanned scope.** This is limited evidence, not a guarantee about the entire public repository, all history/artifacts or private host state. No credential rotation, history rewrite, visibility/permission change, signing-material access or phone action.

## Method and snapshot

Official [Gitleaks v8.30.1](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1), extracted temporarily rather than installed globally. Archive SHA256 `551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb` matched the upstream checksums file, GitHub release-asset digest and the earlier audit pin; checksums-file SHA256 `061476c21adaf5441516f96f185c1a4706a83cd6329b9b38762271b3d4a52fae`. These matching publisher surfaces do not independently authenticate the publisher/toolchain.

Explicit config extended pinned default rules with a supplemental long GitHub installation-token pattern (`ghs_`/`ghu_`, including dotted stateless-shaped bodies). A fabricated520-character probe was detected and its JSON secret field verified fully redacted before scanning. No real credential was used as a probe. Scans used100% redaction, an empty ignore file, ignored inline allow comments and added no repository suppression. Repository content was not sent to a scanner service.

The local fetched/ref snapshot contained1,746reachable commits,20,246objects and4,740unique blobs. Ref-snapshot SHA256 `370bbee6df8832a2fe2a1ac9bf8f6afcdbe5cdf2c4071a58377ae204e4c6468b`. It predates the final #701 integration head and #715 proof; their new commits are not claimed as part of this historical snapshot. This was not a fresh fetch of every GitHub remote/PR ref.

- Diff-history scan:1,271non-merge/diff commits reported,15,404,614bytes; two generic-key candidates.
- Independent reachable-blob scan:4,637UTF-8/non-NUL text blobs,80,271,932bytes; eight candidates. Text extraction capped individual blobs at32MiB; no oversized blob was skipped.
-103binary/non-UTF-8 blobs excluded from text decoding. Their observed paths contained none of the checked common credential/key-container extensions; this is path evidence, not binary/archive analysis.

Both scans exited with the configured candidate-found code2, not a clean/no-candidate exit. All candidates were manually classified; raw reports, extracted contents and command logs remain local and are not uploaded.

## Candidate disposition

| Source | Classification / evidence |
| --- | --- |
| `app/src/test/java/dev/avery/muon/PartitionCompletionIndexTest.kt` (history commit `5a2a644bfe80deae27f9832aba0cf65f6109a130`, line28) | Test identifier passed as DownloadRequest.id/customCacheKey and SQLite fixture data, not an authorization credential. Seven blob versions repeat this same harmless constant. |
| `docs/handoffs/2026-10-09-disk-status-bootstrap.md` (history commit `663bdff922f15a5dbe471bc6db697ea43ed11adf`, line11) | Ordinary English acceptance-check prose after “saved access during bootstrap,” matched by a generic-key heuristic. Not an encoded token or secret. |

Thus two history candidates and eight blob candidates represent the same two false-positive categories. No broad scanner allow rule was added and no suspicious literal was copied into this public report.

## Limits / preventive controls

Current repository API readback also showed secret scanning/push protection enabled and no open secret-alert metadata. Main retained strict required build/branch checks, administrator enforcement and no force-push/deletion; external fork approval was all contributors. No settings were changed. API alert absence is independent limited evidence, not proof of complete scanner coverage.

This run did not repeat September30's public issue/comment/release/log/report-content collection, nor scan all later Actions logs/artifacts, caches, attachments, archives, binary payloads or private/untracked host files. The [September30 report](2026-09-30-public-readiness.md) retains its historical scope; dependency/tool provenance remains under the [accepted maintenance disposition](2026-10-07-build-trust-disposition.md). Recheck new refs and public surfaces when their contents materially change.
