# Mixed-route reader partial ownership — 2026-10-11

Solo GPT-6 / Codex desktop; exact variant/effort not reported. No Claude/device use. Successor to OPEN #494. Production partition mode remains disabled; application acceptance open.

MixedSavedAudio could lose an unreturned legacy factory participant: its failed open closed a null child and reset Idle, letting the outer barrier release its permit. It now retains the exact selected audio/factory/failure and returns typed partial ownership; returned child and typed partial read/listener owners stay retained without finally resetting Uncertain. Actual close failure returns typed ownership. Known route refusal before construction still drains, and nested close refusal before I/O is not itself unknown close.

Three additional native/actual-file controls cover unreturned legacy descriptor across mixed+outer wrappers, typed partial read holding both returned child and partial descriptor, and known preconstruction route refusal draining normally. CI is first compile; final exact head/run/report/APK receipt belongs on PR. No phone/performance proof. Continue actual writer/index/census/native lifecycle coverage and production partition startup/opt-in/recovery controls after source/CI checks. POCO useful for the later enabled production/recovery candidate, not needed here.
