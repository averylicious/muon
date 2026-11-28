# #253 streamed runtime played names — 2026-10-09

Continuation of #423 on the unmerged main-audit acceptance stack. GPT-6 / Codex desktop implemented/self-checked, exact variant/effort not exposed; allocated Claude exhausted/idle, no phone QA this cycle.

The complete derived span census now also streams distinct exact UTF-16 played keys in order, under the native cache's lock. Runtime saved-page projection, Connect's playable count and duplicate played-copy lookup avoid full native key-set copies and an extra sorted Kotlin list. Metadata-only/partial played resources still remain invisible, as before; full copies, unknown/oversized metadata, protected ownership, count and ordering are preserved. Default key enumeration stays for small fixture/legacy constructors; production explicitly uses the disk-backed order.

The derived key census is unavailable until all native startup callbacks complete. Failed/incomplete census reads throw, rather than publish an empty/partial saved catalog. Atomic catalog consumers retain the prior complete generation. The playable-count caller preserves its existing failed-shelf behavior. Disk IO/emit errors still propagate with cursors closed.

Played Clear re-queries one eligible whole resource after each deletion, closing the cursor before callbacks mutate the order. It honors the complete protected-claim gate and never removes downloads. This still uses supported native resource removal, whose per-resource span snapshot is separate work.

Native tests compare actual inventory and count with the prior path while making any native getKeys call fail; exercise multi-span DISTINCT and early exit, empty/partial copies and original-byte protection, reject unavailable censuses, and verify protected/complete Clear. CI first compile/full tests/lint; final receipts on PR/coordinator checkpoint. UAT pending for offline count/reload/recent-copy/clear/restart, no phone results claimed.

Still required: SimpleCache's native eager content/metadata/span residency, naming's all-native-key census and destructive extent snapshots; compact manager bootstrap/marker/byte-total holders should be reconciled in the remaining holder map. #401 still needs a later authorized runtime comparison. No app merge/Stable/tag/publication or experimental work.
