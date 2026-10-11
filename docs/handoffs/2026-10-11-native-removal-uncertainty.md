# Native manager removal uncertainty — 2026-10-11

Solo GPT-6 / Codex desktop; exact variant/effort not reported. No Claude/device use. Successor to OPEN #493. Partition mode disabled; application acceptance open.

LegacyDownloadFactory.remove owns real mutation admission, but its empty source/sink tracker previously looked clean even when Cache.removeResource threw. It now distinguishes actual entry and known return: an entered throwing opaque removal retains the exact Task (and therefore cache/key), tracker, failure and shared permit, quarantines eviction and refuses replacement. Known pre-admission refusal does not poison a healthy task; known successful removal drains normally and future save works. Native manager/index deletion policy before a removal is still a separate requirement; this does not claim rollback after an unknown mutation.

Three added native controls cover thrown removal/no retry, real successful removal followed by save and pre-admission refusal followed by a healthy mutation. CI is first compile; exact head/run/downloaded reports/APK receipt belongs on PR. No hardware/performance proof. Continue source/CI repair before next task, then remaining production binding/removal policy and opt-in/recovery controls. No device needed for this boundary.
