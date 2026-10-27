# Saved-cover temporary cleanup

Source assessment / candidate, 2026-10-27. Main baseline20579cf1; separate post-RC maintenance, not a new frozen .1420 gate. No phone, user music, installed app, settings or signing changes.

`DownloadArt.fetchEntry` left its `.part` file after a partial write or failed publication even when both observable closes succeeded. Deleting that entry removed its published cover only. Repeated failures for distinct entries could leave optional-cover bytes behind; no measured device accumulation or audio loss is claimed.

The candidate gives each fetch its own temporary and attempts to remove only that temporary after confirmed closes, whether publication succeeded, writing failed or rename failed. Old `.part` files, other entries and unexpected target directories are preserved. A source/output close that remains uncertain still retains its exact handle and file, quarantines the existing storage admission and refuses cleanup/retry; no orphan purge or forced close is added. Filesystem deletion can fail, so this is not a disk-space guarantee.

Two focused tests use real disposable outputs and OkHttp response/source objects with a no-network interceptor: partial write/known closes/cleanup/healthy retry, and rename refusal with an unexpected target directory. Existing successful, capped-response and unknown-close cases retain their byte, exact-handle, no-reclose, no-publication and admission assertions; they now identify the actual unique temporary rather than a fixed filename. Actions will be the first compile; no local Android build or device acceptance.

GPT-6 / Codex desktop implementation/source self-review; exact variant/effort not reported. Independent review pending. Keep the candidate separate and open until its final-head checks pass and later saved-cover acceptance is arranged. The historical #646 factory-reset refusal is not reproduced or fixed here.
