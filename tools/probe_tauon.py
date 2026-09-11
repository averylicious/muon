#!/usr/bin/env python3
"""Read-only real server probe. Saves one local FLAC + private report; never controls desktop playback."""
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
import urllib.request
import urllib.parse
import urllib.error

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs): return None

def main():
    p = argparse.ArgumentParser(); p.add_argument('origin'); p.add_argument('--output', default='proof-private')
    args = p.parse_args()
    u = urllib.parse.urlsplit(args.origin)
    address = ipaddress.ip_address(u.hostname)
    assert u.scheme in ('http', 'https') and (address.is_private or address.is_loopback)
    assert not address.is_unspecified and not address.is_multicast
    assert not u.username and not u.query and not u.fragment and u.path in ('', '/')
    base = args.origin.rstrip('/')
    opener = urllib.request.build_opener(NoRedirect)
    def get(path, headers=None):
        return opener.open(urllib.request.Request(base + path, headers=headers or {}), timeout=20)
    def getjson(path):
        with get(path) as r: return json.load(r)
    assert getjson('/api1/version')['version'] == 1
    selected = None
    for playlist in getjson('/api1/playlists')['playlists']:
        assert str(playlist['id']).isdigit()
        tracks = getjson('/api1/tracklist/' + str(playlist['id']))['tracks']
        selected = next((t for t in tracks if t.get('can_download') and str(t.get('path','')).lower().endswith('.flac')), None)
        if selected: break
    if not selected: raise SystemExit('No downloadable FLAC in exposed playlists')
    tid = selected['id']; assert isinstance(tid, int) and tid >= 0
    endpoint = f'/api1/file/{tid}'
    dest = Path(args.output); dest.mkdir(parents=True, exist_ok=True)
    with get(endpoint) as r:
        assert r.status == 200
        headers = dict(r.headers)
        payload = r.read(256 * 1024 * 1024 + 1)
    assert len(payload) <= 256 * 1024 * 1024, 'Probe limit 256 MiB'
    assert payload.startswith(b'fLaC'), 'Not native FLAC'
    assert len(payload) == int(headers['Content-Length'])
    report = {'origin':base, 'track_id':tid, 'duration_ms':selected['duration'],
              'bytes':len(payload), 'sha256':hashlib.sha256(payload).hexdigest(), 'headers':headers, 'ranges':[]}
    n = len(payload)
    for value, start, end in [('bytes=0-15',0,15),('bytes=65536-',65536,n-1),('bytes=-16',n-16,n-1)]:
        with get(endpoint, {'Range':value}) as r:
            body = r.read(); assert r.status == 206
            assert r.headers['Content-Range'] == f'bytes {start}-{end}/{n}'
            assert body == payload[start:end+1]
            report['ranges'].append({'request':value,'status':r.status,'content_range':r.headers['Content-Range'],'byte_match':True})
    try: get(endpoint, {'Range':f'bytes={n}-'})
    except urllib.error.HTTPError as e:
        assert e.code == 416 and e.headers['Content-Range'] == f'bytes */{n}'
        report['unsatisfiable'] = 416
    else: raise AssertionError('Expected 416')
    (dest / 'track.flac').write_bytes(payload)
    (dest / 'tauon-probe.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))

if __name__ == '__main__': main()
