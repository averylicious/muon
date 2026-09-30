# Notification artwork resource limits

#220, codex/notification-artwork-bounds, main baseline 792df8c2d9ad09938630e9b516b9b90bbf536ca7. GPT-6 / Codex desktop; effort not reported; authorship/self-review only.

The notification loader was a separate uncapped path: pinned Media3 DataSourceBitmapLoader defaults maximumOutputDimension to LENGTH_UNSET, reads with DataSourceUtil.readToEnd, and decodes without the app's Compose artwork cap. This corrects the earlier network audit's no-finding response-cap claim: its Compose/DownloadArt caps did not establish service-loader bounds.

The service now builds the pinned bitmap loader with maximum side 512px, a 4MiB source cap (known-length rejection or unknown-length cap plus one detection byte), and embedded-image size rejection. Source closes via the actual loader's finally. Encoded media input may allocate bounded loader buffers before decode; no claim of an overall app heap budget or native-code security. Returned oversized art is refused instead of causing an unbounded read. Five actual loader fixtures cover ordinary art, declared/unknown oversize, embedded oversize and large compressed-image sampling. CI is first compile/test; no phone verification.

No audio client deadline changes here. Preserve #206's metadataClient deadline when reconciling PlaybackService; that open PR touches the same loader construction. A byte cap alone does not stop a slow-drip response under the cap. Source inspected: Google's Maven published media3-datasource 1.11.0 DataSourceBitmapLoader/DataSourceUtil; Builder API and defaults checked. Experiment uses the same inspected service path; port after main review.

Manual QA pending: notification/lockscreen art across tracks, pause/resume, missing/corrupt cover (controls still work), long/large art, background playback. Keep PR open until applicable checks/user QA. Upload quota may block artifacts. No phone/Claude/experiment checkout or secret access. Exact head/results on PR.
