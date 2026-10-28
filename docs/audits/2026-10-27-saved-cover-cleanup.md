# Saved-cover temporary cleanup and path checks

Updated2026-10-28. Existing draft [#719](https://github.com/averylicious/muon/pull/719), main baseline20579cf1. Post-RC maintenance; no frozen .1420 gate, phone, user music, installed app, settings or signing change.

## Changes

A failed saved-cover write/rename could leave a partial file after both observable closes succeeded. Each fetch now owns a unique temporary and attempts cleanup only after known closes. Unknown source/output closes still retain the exact handles/file, quarantine existing admission and refuse cleanup/retry. No historical orphan purge, audio deletion or disk-space guarantee.

A later source check found an inconsistent path boundary: confirmed Move/Delete already use checkCoverPath, but ordinary hasEntry/forEntry/fetchEntry/removeEntry could follow pre-existing file/directory links. Ordinary calls now reuse the same check before reading/fetching/removing and around publication/known-close cleanup; reads recheck before returning and fetches recheck after network work before creating output. Unsupported paths yield absent optional art, a skipped optional fetch or an IOException for removal. They do not authorize deletion outside the cover path.

Production constructs both Legacy and partition cover stores under context.filesDir.canonicalFile/downloads-art, including covers for SD songs. Thus this is private-path defensive consistency, **not an SD-writer attack route or demonstrated externally reachable vulnerability**. Forging these private links would require another means of modifying app-private storage. Existing Android user0 aliases are canonicalized by the caller before this store is constructed. These checks do not atomically defeat arbitrary concurrent filesystem replacement or prove inode identity for every ordinary read/write; no race-free or global security guarantee.

## Evidence

Prior cleanup-only `59ccdbb1ae70783f36becc1e0eb06fb9aaf7058a` / [Android .1506](https://github.com/averylicious/muon/actions/runs/40452824139) passed downloaded1,823cases/variant, zero failures/errors/skips; lint0errors/65warnings plus3hints. That input is included in verified25-topic #701/4721/.1581. It does not validate the new path-check delta.

Two new test methods use disposable real filesystem paths: file link, linked cover directory/parent and empty linked directory do not expose other bytes, fetch, remove other files or unlink unsupported links; a no-network OkHttp interceptor replacing the directory with a link during the response verifies known source closure and no output creation. Existing ordinary publication, byte-cap, cleanup, unexpected-directory and uncertain-close/handle/admission controls remain. Fixtures never touch user files; cleanup walks without following links.

**Verified path-check `ccbfa9202a45b04dc0d71157f5e43eab0c4b91cd` / [Android .1584](https://github.com/averylicious/muon/actions/runs/40582254296):** downloaded1,825cases/variant; all four DownloadArt/fourteen lifetime cases in both; zero failures/errors/skips, lint0errors/65warnings plus3hints. Archive digest1ceef1980df962466da51f27cb34d3010ccb3b740f84cf4aef2c800ae444672e matches API metadata. New #701/b02a/.1589 includes this follow-up, with separate combined verification pending. No local Android build, real-device filesystem or exploit test. Later normal saved-cover appearance/Save/Delete/Move compatibility remains acceptance, not a new frozen-RC gate. #646's historical post-reset refusal remains unresolved.

## Attribution / next action

GPT-6 / Codex desktop (exact variant/effort not reported): source assessment, implementation/tests and author self-check. Allocated Opus5.5 / Claude Code / High explicitly selected independently reviewed the earlier fetch/close cleanup without a blocker; its earlier review did not cover this new path delta. Root verified canonical production callers and the cleanup/unknown-close contract. No independent final path-delta or whole-candidate review claim. Verify new exact-head CI, fix failures, then deliberately refresh #701 and reverify the combination if capacity permits. Keep draft/open and uninstalled.
