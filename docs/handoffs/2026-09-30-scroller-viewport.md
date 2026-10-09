# Alphabet scroller small-viewport checkpoint

Main baseline 792df8c2d9ad09938630e9b516b9b90bbf536ca7; branch codex/scroller-small-viewport. GPT-6 / Codex desktop, effort not reported; author self-check only.

#228: handle/bubble offsets used a negative coerceIn upper bound when a visible/held scroller viewport shrank below 48/56dp, causing an exception. Clamp available placement range to at least zero through a shared pure geometry helper. Normal layout math unchanged; tiny viewport pins to top instead of throwing. Two boundary-focused JVM cases cover zero/smaller/equal viewport for both elements and normal center/edge placement. No Compose UI/phone/layout test claim; CI first compile/full tests/lint. No dependencies/settings/artwork/gesture redesign.

Phone QA pending: Songs A-Z long list; show/hold scroller, resize/rotate/split screen to a short list viewport; no crash, normal thumb/bubble placement returns when space grows. No phone access. Same geometry at inspected experiment e1bf045c1fa7139c4966e480f2f06941a703ddfc; no experimental edits. Leave open for latest-head checks and QA. Quota blocks APK uploads until separately resolved; no artifact deletion.
