# Retained saved metadata before-decode bound — October 7

Source baseline #372 `45b3d2c3398bcd3e3cc8f4140cd5a0fb307e8e71`, containing #370 and main `89bb29acc098d98250b33f062284ea0b48012c96`. GPT-6 / Codex desktop, exact model variant/effort not reported: implementation and source self-review, not independent review. No phone/experiment work or new Claude task.

## Gap and change (#253)

`savedInventory` called `decodeSong` for every retained download/played record, then `SavedEntry.displaySong` applied the 16 KiB safe-display budget. Legacy oversized records therefore still allocated UTF-8 strings, delimiter arrays, Base64 output and decoded track objects before their tags were hidden from UI/IPC.

The production inventory now checks encoded record size first. Above the existing 16 KiB limit it skips decoding, keeps the saved locator/completeness/removal rules, and shows the existing explicit metadata-too-large label. Exactly16 KiB is still decoded and then passes through the unchanged decoded/display guard. Original index/cache metadata and audio are not rewritten or deleted; normal records and corrupt-small-record handling are unchanged. This limits each decoder input and prevents retaining that oversized decoded track, not the original raw database/cache allocation.

Two real native-index/SimpleCache controls exercise a700k-title download and played records exactly at/one byte above the boundary. Assertions cover null decoded projection, safe item handle/label, preserved stored metadata/audio and playable coverage. Existing direct SavedEntry display/IPC controls remain; the general codec remains compatible and unbounded for its non-production characterization callers.

## Limits and handoff

All saved rows/cache metadata still enter memory, list length/combined inventory has no aggregate cap, JSON DOM still precedes projection, and shared Binder/native memory remains separate. No measured OOM/jank/heap benefit. #253 stays open; selecting a total/paging budget for large saved collections needs its own compatibility design and focused tests. This child includes #372 and every earlier app prerequisite; leave OPEN for user acceptance. Actions is the first Android compile; exact-head results/artifact must be recorded on the PR. No automated device QA this cycle, Stable release or dependency change.
