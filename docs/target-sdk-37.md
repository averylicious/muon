# Targeting Android 17 (API 37)

Research notes, 2026-09-25, written ahead of a later enhancement: raising `compileSdk` and `targetSdk` from 36 to 37. The user asked for this work to follow the current slices and the next Stable promotion, with local network privacy as the main interest. **Nothing here changes the build.** Muon still targets 36.

Sources were read on 2026-09-25. Each finding says whether it was checked in a primary source or remains unverified.

## Summary

- **The one change that matters for Muon is the local network permission.** Every connection Muon makes is to the local network: NSD discovery, the Tauon API, artwork and Media3 streaming. At `targetSdk 37`, all of it is blocked until the user grants `ACCESS_LOCAL_NETWORK`. Raising the target without a permission flow would break the app completely, and blocked TCP connections time out rather than failing clearly.
- **The privacy benefit is real, and it is the reason to do this.** At `targetSdk 36`, Android 17 gives Muon local network access *implicitly* through `INTERNET`. At 37, access becomes an explicit runtime permission in the Nearby devices group, which the user can grant, deny or revoke. A system picker may even let the user pick the one Tauon server without granting broad access. Muon would then ask for exactly what it uses.
- The other Android 17 changes either don't touch Muon or already apply to it on your phone, whatever it targets (details below).
- Tooling is already close. AGP 8.13.2 is above the documented minimum. CI must install the API 37 platform, which it doesn't yet.

## Local network permission (the main change)

From [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission) and [Behavior changes: apps targeting Android 17](https://developer.android.com/about/versions/17/behavior-changes-17), both primary sources:

- **Who is affected:** apps targeting 37 or higher are enforced on Android 17. Apps targeting 36 or lower that hold `INTERNET` get an implicit `ACCESS_LOCAL_NETWORK` grant. The page calls this temporary: access is blocked by default once the app targets 37.
- **The permission:** `android.permission.ACCESS_LOCAL_NETWORK`, a runtime permission in the `NEARBY_DEVICES` group (the same group as Nearby Wi-Fi and Bluetooth). If the user has already granted another permission in that group, they are not asked again.
- **What it covers:** all networking APIs, because the check sits deep in the network stack. That includes outgoing and incoming TCP, UDP unicast, multicast and broadcast, and mDNS `.local` resolution. Traffic to a DNS server on the local network (port 53) is exempt.
- **What a denial looks like:** TCP typically **times out**, and UDP typically gets `EPERM`. For Muon, a denied permission would look exactly like an unreachable server unless the app checks the permission first.
- **Don't request early:** the guidance is not to request `ACCESS_LOCAL_NETWORK` at runtime before targeting 37.
- **Asking again:** the page describes a "permission reset counter" mechanism, giving an app more chances to call `requestPermission()` after an early denial.
- **Testing on Android 16:** `adb shell am compat enable RESTRICT_LOCAL_NETWORK <package>` (in Android 16 the gate used `NEARBY_WIFI_DEVICES`). Device testing is the user's; no agent will run this.

### What it means for Muon's code

- `ServerEndpoint.parse` only accepts `10/8`, `127/8`, `172.16/12` and `192.168/16`. So every endpoint is local, and there is no internet traffic to keep working without the permission.
  - **Unverified:** whether loopback (`127/8`) counts as local network. The page doesn't say, and loopback matters only for a Tauon instance on the phone itself.
- `LibraryModel` reconnects to the saved server at start-up (`init { if (address.isNotBlank()) connect() }`). At 37, that must first check the permission, or a denied or revoked permission becomes a long timeout with a misleading "can't reach server" message.
- `ServerDiscovery` uses `NsdManager.discoverServices` for `_tauon-remote._tcp`. Discovery, the resolved connection and playback all need the permission, unless the picker below is used.
- `PlaybackService` streams over HTTP from the same server. It shares the app's grant, so there is nothing separate to request. Revoking a permission in Settings normally stops the app's process; how Muon then resumes should be covered in QA.

### Two ways to comply

**A. Request `ACCESS_LOCAL_NETWORK` (broad).** Declare it, then on Android 17+ check and request it before any connection, discovery or reconnect. Explain why on the Connect screen, and handle denial with a clear message and a link to Settings. This works for both typed addresses and discovery. The user can see and revoke the permission as Nearby devices.

**B. Use the system NSD picker (narrow).** The page shows `DiscoveryRequest.Builder(...).setFlags(DiscoveryRequest.FLAG_SHOW_PICKER)` with `NsdManager.registerServiceInfoCallback`. The system shows its own dialog, the user picks one device, and connections to the addresses it returns don't need `ACCESS_LOCAL_NETWORK`. For a privacy-minded user, this is the most attractive option: Muon would see and reach only the Tauon server that was picked.

Open questions for B (**unverified**; answer these before designing it):
- **API level and availability.** `DiscoveryRequest` exists in public AOSP `main` (checked in `packages/modules/Connectivity`, `framework-t/src/android/net/nsd/DiscoveryRequest.java`), but `FLAG_SHOW_PICKER` does not appear there. The Android 17 source may not be published yet. A secondary source says `DiscoveryRequest` arrived in API 35. The flag's API level is unconfirmed.
- **Persistence.** Does the picker's grant survive an app restart, a Tauon restart or a DHCP address change? Muon reconnects automatically, so a picker that must reappear on every launch would be worse than option A.
- **Typed addresses.** An address typed by hand doesn't come from the picker, so it would still need option A, or it could be dropped in favour of discovery.

A likely design is B as the default path, with A as the fallback for typed addresses. That depends on the answers above, so it isn't decided here.

## Other Android 17 changes, checked against Muon

From the primary [behavior-changes-17](https://developer.android.com/about/versions/17/behavior-changes-17) and [background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio) pages:

| Change (target 37 unless noted) | Effect on Muon |
|---|---|
| **Background audio hardening.** Every app on Android 17 must have a visible activity or a non-short foreground service for background playback, audio focus and volume changes. Targeting 37 also requires a foreground service with while-in-use capability. | Muon uses Media3 `MediaSessionService`, which the page recommends as "not likely to be impacted". The all-apps part already applies to your phone today. At 37, check QA for resuming from the notification, Bluetooth or Quick Settings after a long pause, and for volume changes from the player. |
| **ECH and Certificate Transparency by default** | Only affect TLS. Muon uses cleartext HTTP to the LAN only. No effect. |
| **Orientation and resizability ignored on screens ≥600dp** | Muon sets no orientation or resizability restrictions. No effect, but worth a tablet or foldable QA pass. |
| **Lock-free `MessageQueue`; `static final` fields unmodifiable by reflection; native libraries loaded read-only** | Muon has no reflection on these and no native code of its own. Libraries (Media3, Compose) are the only possible exposure. None is known. |
| **`RemoteViews` bitmap memory limit** | Muon has no widgets. The media notification is built by Media3. No known effect. |
| **Background activity launch rules extended to `IntentSender`** | Muon doesn't launch activities from the background. No known effect. |
| **SMS OTP delay, Contacts provider restrictions, password display, Bluetooth RFCOMM `read()`, content capture** | Not used. No effect. |

Predictive Back and edge-to-edge were already enforced by earlier targets (35 and 36). Muon handles both today.

## Build and CI changes a bump would need

- `app/build.gradle.kts`: `compileSdk = 37`, `targetSdk = 37`. `minSdk` stays 28, and the new permission is simply absent on older Android versions.
- **AGP:** [Set up the Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk) gives a minimum of AGP 8.9.0-rc01. Muon's is 8.13.2 (`build.gradle.kts`). Android Studio Meerkat or newer is recommended.
- **CI:** `.github/workflows/android.yml` installs `platforms;android-36` and `build-tools;36.0.0`. It must install `platforms;android-37`, and build-tools 37 as the setup page recommends. **Unverified:** whether the pinned Compose, Media3 and AndroidX versions compile warning-free against 37. CI would be the first check (see AGENTS.md, Build and verification).
- **Version code:** unaffected. Installing over the existing Canary and Stable apps works as today, and signing and package IDs don't change.

## Proposed later PR (not started)

1. Build and CI: SDK 37 platform, `compileSdk`/`targetSdk` 37.
2. Manifest: `ACCESS_LOCAL_NETWORK`.
3. A small pure helper deciding "may connect / must ask / denied, explain" from the SDK level and permission state, with unit tests. It gates start-up reconnect, Connect, discovery and Retry.
4. Connect screen: a short rationale before the system prompt, a denial state with a Settings link, and no silent timeouts.
5. Optional, only if the open questions above resolve well: the NSD picker path.
6. Device QA by the user on Android 17:
   - first run;
   - deny, then allow;
   - revoke in Settings while playing and while idle;
   - reconnect after a restart;
   - discovery;
   - background playback and notification controls.

## Sources

- [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission) (Android Developers)
- [Behavior changes: apps targeting Android 17 or higher](https://developer.android.com/about/versions/17/behavior-changes-17) (Android Developers)
- [Background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio) (Android Developers)
- [Set up the Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk) (Android Developers)
- AOSP `packages/modules/Connectivity`, `framework-t/src/android/net/nsd/DiscoveryRequest.java`, `main` branch (checked for `FLAG_SHOW_PICKER`; not present)
