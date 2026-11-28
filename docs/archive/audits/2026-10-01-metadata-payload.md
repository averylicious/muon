# Metadata transport characterization — 2026-10-01

Inspected main: `5997212ece3049b5b6e5c49d3cebd4a1ccc2928f`. Follow-up to #203 Q2 / #253; no app limits, schema changes or production fixes. GPT-6 / Codex desktop (Sol), effort not reported; author investigation and test implementation, no independent review.

## Source-supported route

TauonApi.tracks accepts text fields without individual length limits, within a16MiB response cap. TauonTrack.mediaItem copies title/credits/album fields into MediaMetadata and the entire encoded offline record into its extras. OfflineStore.add puts the entire encoded record in DownloadRequest.data and sends DownloadService.sendAddDownload. No user library/phone was inspected for this slice.

Pinned published Media3 1.11.0 [common](https://dl.google.com/dl/android/maven2/androidx/media3/media3-common/1.11.0/media3-common-1.11.0-sources.jar), [session](https://dl.google.com/dl/android/maven2/androidx/media3/media3-session/1.11.0/media3-session-1.11.0-sources.jar) and [exoplayer](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.11.0/media3-exoplayer-1.11.0-sources.jar) sources inspected:

- MediaControllerImplBase sends media-item lists through BundleListRetriever. BundleListRetriever.onTransact checks reply size before writing each entire Bundle; it does not divide one oversized item. Its in-process getList shortcut returns the original list without a transaction.
- MediaMetadata.toBundle bounds/chunks artwork separately but places title/artist/album text and extras directly in the Bundle. Artwork chunking does not bound those fields.
- DownloadService.buildAddDownloadIntent puts a Parcelable DownloadRequest into the service Intent; DownloadRequest.writeToParcel writes data as one byte array. A same-app service start still goes through Android's service-start machinery; same-process list shortcut is not a blanket exemption for every route.

## Disposable test evidence and limits

MetadataPayloadCharacterizationTest calls the real Muon media-item factory/codec and actual pinned Media3 serializers. The retriever case invokes its actual protected onTransact method reflectively, avoiding getList's local shortcut, with disposable Parcel objects. The download case serializes/restores the actual built service Intent without starting a service.

Three cases cover an ordinary round-trip, a700,000-character flat title whose metadata/redundant offline bytes form a reply over1MiB, and a1,100,000-character title whose download Intent is over1MiB. These expectations characterize a supported risk; CI is the first actual compile/execution and results must be recorded on the PR. They do not establish a device TransactionTooLargeException, kernel Binder allocation, Android17 behavior, UI audibility, heap/time benchmark or practical likelihood. A1MiB comparison is a fixture scale, not a universal per-message safe allowance: actual Binder buffers are shared, other fields/routes differ, and the service/controller topology matters.

No arbitrary field truncation or new app limit is implemented. The ordinary record format and app behavior remain unchanged. #248's exceptional NUL codec, #252's depth cap and #245's IO projection do not settle this flat-field problem; preserve all during later integration.

## Next bounded fix design

Define field/item/aggregate limits from legitimate-library requirements and measured representation sizes. Prefer recoverable, clearly explained refusal before unsafe transport over silent metadata truncation. Consider a compact internal identity/reference for offline extras, but preserve retained records, cross-process reconstruction and offline availability; removing SONG_EXTRA blindly breaks played-copy metadata. Keep policy/implementation separate from this characterization. Device reproduction requires new authorization and disposable fixtures; source-only/desktop tests cannot prove phone stability. No phone QA is needed for this test-only PR; app fixes remain pending.
