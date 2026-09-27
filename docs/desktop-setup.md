# Desktop setup and troubleshooting

Muon plays music from Tauon Music Box running on your computer. The music streams over your home network, and nothing is sent to the internet.

## Setting up Tauon

1. In Tauon's settings, turn on **remote control / server for remote app**, then **restart Tauon**. Listen Along is a different server, and Muon doesn't need it.
2. Keep your music in a Tauon playlist. Muon lists the tracks of every playlist Tauon exposes. It can't play CUE-sheet segments, or tracks that come from another network service rather than a local file.
3. Put the phone on the same network as the computer.

## Connecting

On first launch, Muon asks for Android's local network access (Android 17 and later), then looks for Tauon two ways:
- **mDNS:** Tauon announces itself as `_tauon-remote._tcp` when the optional Python `zeroconf` package is installed.
- **A port scan:** Muon checks port 7814 on the other addresses in the phone's /24 network.

If it finds exactly one Tauon, it connects. Otherwise, type the address, for example `192.168.1.10:7814`. You can find your computer's address with `ip -brief address`.

Muon remembers the server and reconnects when you reopen it. To switch servers, use **Settings → Disconnect**.

## When it won't connect

1. **Is Tauon answering?** On the computer, run:
   ```sh
   curl http://<computer-address>:7814/api1/version
   ```
   It should print `{"version": 1}`. If it doesn't, the remote-control setting is off, or Tauon wasn't restarted after turning it on.
2. **Is a firewall in the way?** If `curl` works on the computer but the phone can't connect, the firewall is the usual cause. On a machine using UFW, allow only the phone, only on this port:
   ```sh
   sudo ufw allow in from <phone-address> to <computer-address> port 7814 proto tcp comment 'Tauon from phone'
   ```
   To undo it, run `sudo ufw status numbered`, then `sudo ufw delete <number>`. The rule needs updating if either device's address changes.
3. **Other network settings:** guest Wi-Fi, client isolation and some VPN apps keep devices on the same Wi-Fi from reaching each other.
4. **Discovery only:** for mDNS, the firewall also has to allow `224.0.0.251` on UDP port 5353. The port scan and typing the address both work without it.

## Security

Tauon's remote API has no password and no encryption, and anyone who can reach it can control playback and read file paths. Muon only connects to private network addresses and never opens ports or changes network settings. **Don't forward port 7814 on your router or expose it to the internet.** Reaching Tauon away from home, for example over a VPN such as Tailscale, isn't supported yet. Offline downloads cover being away.

## What Tauon provides, and its limits

- Muon sees the tracks in Tauon's playlists. Tauon has no whole-library, search or paging endpoint, so Muon loads the playlists and searches them itself.
- Streams are the original files. Muon plays FLAC and the common formats through Android's decoders, and very unusual codecs depend on the phone.
- Downloads use Tauon's Opus transcode, fixed at 84 kbps for now.
- Lyrics come from Tauon when it has them stored. Synced lyrics aren't provided.
