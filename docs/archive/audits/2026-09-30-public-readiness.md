# Public-repository readiness check — 2026-09-30

The user temporarily changed Muon to public after private Actions minutes ran out, requested an as-is/hobby-project disclosure and an appropriate license, and authorized continued auditing. This check is limited evidence, not a guarantee that no secret exists or that the app is secure.

## Inspected state and outcome

- Main: `792df8c2d9ad09938630e9b516b9b90bbf536ca7`; experiment: `e1bf045c1fa7139c4966e480f2f06941a703ddfc`. Both were refreshed from GitHub before the scan. Other public branches/tags were fetched too; no experimental checkout was edited.
- Repository API confirmed public visibility. Actions default token is read-only; approving PRs via Actions is disabled. Main strict required build/branch checks, admin enforcement and force-push/deletion protection remain enabled.
- [Run386](https://github.com/averylicious/muon/actions/runs/36710470965) succeeded, with both APK artifacts/reports and [Canary .386](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.386) fully published at main792df. This demonstrates recovery for that run, not verification of open audit fixes. It does not include the parked fixes or experimental UI changes.
- No confirmed credential leak was found in the checked surfaces below. Eight generic-key matches in public build logs were inspected: all were APK signer **public-key SHA-1/SHA-256 digests**, duplicated across aggregate/step logs. These are public verification identifiers, not private signing keys. No broad scanner suppression was added.

## Secret-scan scope and limits

Official Gitleaks v8.30.1 was downloaded from its upstream release; archive SHA256 matched the upstream checksums file (`551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb`). Scanner ran locally with full redaction and without honoring inline allow comments. No repository content was sent to a third-party scanner service.

- Ref snapshot: 206 fetched remote/tag refs, 404 reachable commits and 4,681 reachable Git objects. Gitleaks diff-history scan reported 279 scanned commits and zero candidates. The additional unique-blob scan avoids relying only on the diff-history count.
- Independently extracted 1,153 unique UTF-8 text blobs from those reachable objects for a second scan. The 100 binary blobs were reviewed by path; no `.jks`, `.keystore`, `.p12`, `.pfx`, `.pem`, `.key`, environment credential file or signing recovery bundle path was present. Binary assets were not exhaustively decoded or visually inspected.
- Scanned paginated public issue/PR bodies, issue comments, inline review comments and release metadata, plus all textual entries of successful run386 logs (29 ZIP entries) and report artifact (259 entries). Combined text scan was about 82 MB. The eight digest false positives above were the only candidates.
- Source-reviewed current Android, Baseline Profile and branch-policy workflows: signed builds are push/manual events; the fork-capable PR job is read-only with no signing secrets. No `pull_request_target`/privileged fork execution path was found in those workflows. Signing files live in runner temporary storage and are removed; APK artifact paths explicitly contain APK/checksum/build text, while report paths contain reports/results/mapping. No secret-bearing directory is uploaded by the inspected workflow. APKs necessarily contain public signer certificates; signing private keys are not APK contents.

This is not an exhaustive scan of every historic Actions log/artifact, cached runner contents, binary/archive payload, discussion/wiki/attachment or image. Git history may publicly expose author metadata; documents/screenshots include app/library examples and local development details, which are not treated as credentials by this scan. Making the repository private later cannot erase copies already obtained publicly. No history rewrite, credential rotation, artifact deletion, billing change or visibility change was performed by this agent.

## Preventive controls and license

GitHub secret scanning and secret-scanning push protection were disabled on arrival. Both were enabled and read back through the repository API within the user-requested leak-prevention scope. The open-alert query returned no alerts at that moment; initial/background scanning may continue and supported-pattern detection is not universal. Existing CI trust/secret boundaries were preserved.

Added the standard MIT license to original Muon code and project-authored documentation, with copyright attribution to Avery (averylicious) and Muon contributors. The README explicitly discloses AI-assisted/vibe-coded hobby development, incomplete audit, as-is delivery and no warranty/support guarantee. Font/icon licenses and copyright notices remain unchanged; screenshots' music/art/lyrics are explicitly excluded from the project license. MIT permission on distributed copies is not revoked by later private visibility. This is not a full third-party license-compliance audit.

## Verification and follow-up

Local existing checks: 35 CI tests and six release tests passed; disclosure/license checks and diff/branch preflight passed. This documentation PR needs final-head required CI before merging; device QA is not needed and no new APK is promised for its docs-only branch. Latest-head reruns were requested for open checkpoint #231 after external recovery; inspect their actual results before claiming success or landing it.

Keep all app PRs needing phone QA open. Resume from [the continuation checkpoint on #231](https://github.com/averylicious/muon/pull/231); source next steps include disposable move/remove (#230) and optional-copy worker (#225) characterizations. Refresh GitHub secret alerts after its background scan; do not equate no alerts with no secrets. Experimental owners integrate useful main documentation/fixes at their own clean boundary.

Attribution: GPT-6, Codex desktop, effort not reported (user nickname Sol); implementation and author self-check, no independent reviewer or audit Claude session used. No phone access.


### Supplement after GitHub token-format announcement

The [token-format review](2026-09-30-github-token-format.md) found that the pinned local scanner's old installation-token pattern misses a fabricated stateless-shaped value. A temporary supplemental pattern detected it; redacted rescans of fetched history and the collected public text/blob snapshot found no new credential candidates. The eight public-text locations were unchanged, already verified public-key digest false positives. This improves pattern coverage without widening the original historical-log/artifact/binary scope or claiming exhaustive assurance.

#232 source license/disclosure and #235 packaged MIT notice are merged. Both actual #235 APK archives carry the root LICENSE verbatim. Main [run396](https://github.com/averylicious/muon/actions/runs/36717684718) succeeded and published [Canary .396](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.396) at f9f27f75b568c0d2a29e7358d692751f8636c9d4 with nonempty uploaded APK/BUILD/SHA256 assets. This main build excludes the parked app fixes and experiment. Earlier pending-check paragraphs above describe the original documentation-PR stage.
