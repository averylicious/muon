# #179 S3 cache metadata snapshot feasibility — 2026-10-03

Base `2aace9f97e7e1bbdf94a5a2348f1c994c3535219` on branch `codex/cache-metadata-snapshot`. It includes the open prerequisite #285 ([span touch controls](2026-10-03-cache-snapshot-paths.md), with the coordinator's deterministic clock fix). Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not report effort). Author investigation and tests, **not independent review**. Test-only: no production change, device, user cache or music.

**Question:** can a healthy public snapshot carry multiple spans and full current metadata through a durable sidecar round trip and a public fresh-cache import, while the originals are kept?

## Pinned sources

- **Media3 1.11.0** `media3-datasource` sources (`build/sources/datasource.jar`, sha256 `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`, as in the [volume report](2026-10-02-volume-catalog-evidence.md)):
  - `Cache.getContentMetadata` returns the `ContentMetadata` interface (`Cache.java:319`). Its API is getters by key (`get` for bytes, string, long) and `contains`; it has **no enumeration**.
  - `SimpleCache.getContentMetadata` (`SimpleCache.java:514-516`) delegates to `CachedContentIndex.getContentMetadata` (`334-336`). That returns `CachedContent.getMetadata()`, typed `DefaultContentMetadata` (`CachedContent.java:70`), or `DefaultContentMetadata.EMPTY`.
  - `DefaultContentMetadata.entrySet()` returns the entries of its unmodifiable map, whose values are its **internal byte arrays** (`DefaultContentMetadata.java:49-50,66-68`). The byte getter copies (`72-79`).
  - `ContentMetadataMutations.set(name, byte[])` copies the value (`111-112`).
  - The standard keys are `exo_len` and `exo_redir`, and custom keys use the `custom_` prefix (`ContentMetadata.java:32-38`).
- **Android SDK 37** `android/util/AtomicFile.java` (`build/sources/AtomicFile.java`, sha256 `f5274d87b59d3aaa53caaa018b4aa807046d58ecd755a717a6a100c714bdd1b9`):
  - `startWrite` writes a separate new file (123-160).
  - `finishWrite` syncs, closes and renames it over the base (170-183). `failWrite` syncs, closes and deletes it (193-207).
  - `openRead` and `readFully` read the base (241-300).
  - **Sync, close and rename failures are only logged** (`Log.e`), never reported to the caller (174-182, 336-350). AtomicFile has no lock of its own.
  - **Runtime differs from what was inspected:** the tests run under Robolectric's SDK 34 framework, and the SDK 34 `AtomicFile` source was not inspected here. The assertions check observable results, not internal file names.

**Compatibility constraint:** full metadata enumeration depends on casting to the concrete `DefaultContentMetadata`, which is pinned Media3 1.11.0 behaviour. The `ContentMetadata` interface promises no enumeration. A later Media3 version could break the cast, and a production design would need that checked at every upgrade.

## Controls (`CacheSnapshotSidecarTest`; CI pending)

Both use a real `SimpleCache`, native SQLite, disposable folders and the actual `android.util.AtomicFile`. The sidecar sits in a phone-like folder, separate from the cache. There's no reflection, private table, filename parsing or clock dependence.

The **sidecar format is test-only**, not a shipped schema or parser: a version number, the key, each span's position, length and path, then each metadata name with its raw bytes. A trailing byte is rejected.

1. **`twoSpansAndAllMetadataSurviveASidecarRoundTripIntoAFreshCache`**
   - **Setup:** two contiguous spans (0–99 and 100–255) plus metadata: content length, a redirect URI, and a `custom_raw` value that isn't valid UTF-8.
   - **Capture:** from public spans and the copied metadata entries; the expected key set is all three entries.
   - **Round trip:** the snapshot is written with `startWrite`/`finishWrite`, the original cache is released while mounted (shown preserving earlier), and the sidecar is reloaded through a new `AtomicFile`. It's imported into a **new** cache directory using public writes and raw metadata mutations. Only that new cache is released and reopened.
   - **Expected:**
     - two spans at the same positions and lengths;
     - exact payload bytes;
     - equal content length and redirect;
     - exact `custom_raw` bytes;
     - a metadata map identical to the capture.

     The original span files remain, byte for byte, at the captured paths. The old directory is never reopened.
2. **`failedSidecarReplacementKeepsThePreviouslyCommittedCatalog`:** a committed catalog is written. A replacement with an extra entry is started, half of it is written, and it's abandoned with `failWrite`. Reading back through `openRead` and `readFully` on a fresh `AtomicFile` should both give exactly the committed catalog.

## Limits

These controls are **feasibility evidence only**.
- **Not durability or crash safety:** the explicit `failWrite` doesn't simulate process death or power loss, and `finishWrite` reports no sync or rename failure.
- **No atomic cross-cache transaction:** the sidecar, the spans and the new cache's index commit separately.
- **Capture can miss things:** keys not enrolled, commits after capture, and changes while no listener runs. Media3 has no metadata-change listener callback; `Cache.Listener` reports spans only.
- **A path is only a pointer:** files can change or disappear after capture, and the import doesn't check bytes against anything except the source files themselves.
- **Not established here:** cooperative quiescence of writers, storage, path and volume validation, generation binding (#179 S2/S4), and #213 identity.
- **Out of scope:** migration, cleanup, service swap and production change. #179 remains open.

## Next bounded design step

Specify, for review only, a per-volume, generation-scoped sidecar record covering:
- versioning;
- content validation, for example length plus a strong hash per span;
- path containment;
- quiescence (when capture may run);
- recovery for missing or mismatched files.

It should also say how an upgrade check guards the `DefaultContentMetadata` dependency, all before any import code.
