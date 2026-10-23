# v0.2.0 main → M3E integration

User explicitly requested the experimental branch be brought up to the accepted main baseline. Destination `claude/m3-expressive-alpha` at `e1bf045c1fa7139c4966e480f2f06941a703ddfc`; included main `79b5e70e7c10719eeb9e42b7b1e517c85f70acbf`. Separate persistent branch `sync/main-into-expressive-stable-020`; original experiment checkout untouched. PR #651 records exact current heads, CI/artifacts and later merge state.

Ten conflicted files were resolved by combining audited backend/readiness/accessibility behavior with existing experimental presentation. AGP9 resValues and main BuildConfig/resource fixtures both remain. Experimental glass/player morphs, whole-tile flights, expressive Settings/transport/wavy seek feedback and swipe-to-queue remain. Main native-slider labels/buffering semantics and exact-copy Saved actions are retained. The old experimental download progress/Remove block depended on obsolete track-ID ownership; it now uses main's Save copies / On this device contract. This visible integration difference needs owner UAT.

## Wrapper guard integration correction

First experimental run .1357 failed the CI-policy check before SDK/signing because main's distribution-isolation receipt still bound the Gradle8.13 properties, while the existing experiment uses Gradle9.6.0. Dependency inventory104 failed on the same guard. The guard was not disabled.

Read the existing reviewed wrapper install/path/checksum evidence in `docs/audits/2026-10-05-gradle-distribution-refresh.md`. The actual checked-in wrapper JAR is unchanged from main: SHA256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f` (Gradle8.13 wrapper). Thus the existing pinned Install/path source and bytecode review applies to the executable downloader. The experiment changes only distribution URL/checksum; paths stay `GRADLE_USER_HOME/wrapper/dists`, URL validation stays on, timeout remains10000.

Fetched [Gradle's official9.6.0 ZIP checksum](https://services.gradle.org/distributions/gradle-9.6.0-bin.zip.sha256), matching the tracked checksum `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`. Refreshed this branch's full-properties receipt to SHA256 `6c1a6842431b52613b788974881de79d5185ca05daf026723ac8a9faf1663e00`. The experimental-only receipt still fails on arbitrary future property changes; quarantine/path/symlink/environment checks are unchanged. No fetched Gradle code executed locally and no plugin/dependency/runner authentication guarantee is added.

Local Python CI-policy checks and final experimental Actions runs must pass before landing. They are distinct from the accepted stable .1353/.1354 evidence. No phone QA or dependency/Beta upgrade is part of this sync; original experiment pins remain AGP9.4.1 and Material3alpha29. User-visible experimental acceptance remains pending even after compilation/tests.

Attribution: GPT-6 / Codex desktop integration and source self-check; exact variant/effort not reported. No new Claude run or independent review claimed. #646 remains open and unresolved; existing explicit release deferrals persist.
