# #278 local network grant from the Connect screen — 2026-10-02

Base: main `ad1fcf1a01803b1d83c9a7275a70bf9c21f948ee`, branch `codex/permission-discovery-grant`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as assigned (the runtime does not report effort). Author check only, **not independent review**. Not compiled locally: CI is the first compile. Device behaviour is pending the coordinator's Android 17 test.

## Trigger

On Android 17, disconnected, with no saved or typed address, the user taps **Allow** on the Connect screen's "Allow Muon on your network" card (`ConnectScreen.kt:83-95`).
- The permission-result callback in `MuonApp.kt` called `model.connect()` on every grant.
- `connect()` then parsed the empty address, so an address-parser error showed instead of discovery simply starting.

## Change

The callback now connects only when `model.address.isNotBlank()`; that check also treats a whitespace-only address as empty. The resume path (`LifecycleResumeEffect` in `MuonApp.kt`) already had the same check on this base, so it is unchanged.

**Resulting behaviour:**
- **Empty or whitespace address, permission granted:** `LocalNetworkState.granted` turns true, and the Connect screen's discovery effect (`ConnectScreen.kt:40-45`, keyed on it) starts the scan. No connection is attempted and no error is shown. #39 one-server auto-connect then runs from the scan as before (`ConnectScreen.kt:46-53`).
- **Non-blank address:** unchanged. This covers Connect pressed before access (`ConnectScreen.kt:71`), the library's Allow card, and a saved address after an offline fallback: the grant retries. Invalid non-blank input still reaches `connect()` and its validation error.
- **Denial and Settings:** unchanged. Denied state and the Settings path are untouched, and permission state still refreshes on resume.

Not changed: discovery or network policy, `LibraryModel.connect`, signing, data, dependencies, other UI.

## Tests

None added. The change is one condition inside a Compose activity-result callback. There's no Compose UI or activity-result test setup, and pulling the condition out only to test a mirrored Boolean was ruled out. The wiring is verified only by source reading and the CI compile.

## Manual acceptance (Android 17, pending)

1. Fresh state or after Disconnect, address empty: Allow → grant. Discovery starts, there's no "address" error, and one advertised Tauon auto-connects.
2. Same, with several servers: they're listed and nothing auto-connects.
3. Type a valid address before access, press Connect → grant. It connects.
4. Type invalid non-blank text, press Connect → grant. It shows the same validation error as before.
5. Deny, then grant from Settings and return. With an empty address: discovery starts and there's no error. With a saved address: it retries.
6. Library Allow card with a saved address → grant. It retries.
