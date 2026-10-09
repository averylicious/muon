"""Fail closed on unreviewed externally reachable components in the packaged manifest."""
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
COMPONENTS = {'activity', 'activity-alias', 'service', 'receiver', 'provider'}
# Source namespace stays the same in Stable; its application ID has a .release suffix.
REQUIRED = {
    ('activity', 'dev.avery.muon.MainActivity'): True,
    ('service', 'dev.avery.muon.PlaybackService'): False,
    ('service', 'dev.avery.muon.MuonDownloadService'): False,
    ('service', 'dev.avery.muon.MuonCardDownloadService'): False,
}
EXPORTED = {
    ('activity', 'dev.avery.muon.MainActivity'): None,
    ('activity', 'androidx.media3.session.BluetoothValidationActivity'):
        'android.permission.BLUETOOTH_PRIVILEGED',
    ('receiver', 'androidx.profileinstaller.ProfileInstallReceiver'): 'android.permission.DUMP',
}


def verify_manifest(xml, expected_package):
    root = ET.fromstring(xml)
    if root.tag != 'manifest' or root.get('package') != expected_package:
        raise ValueError('Unexpected packaged manifest identity')
    applications = root.findall('application')
    if len(applications) != 1:
        raise ValueError('Expected exactly one application')
    app = applications[0]
    seen = {}
    for element in app:
        if element.tag not in COMPONENTS:
            continue
        name = element.get(ANDROID + 'name')
        exported = element.get(ANDROID + 'exported')
        if not name or exported not in {'true', 'false'}:
            raise ValueError('Component name or explicit exported boolean is missing')
        if name.startswith('.'):
            name = expected_package + name
        elif '.' not in name:
            name = expected_package + '.' + name
        key = (element.tag, name)
        if key in seen:
            raise ValueError(f'Duplicate component: {key}')
        seen[key] = exported == 'true'
        if exported == 'true':
            if key not in EXPORTED:
                raise ValueError(f'Unapproved exported component: {key}')
            permission = element.get(ANDROID + 'permission', app.get(ANDROID + 'permission'))
            if permission != EXPORTED[key]:
                raise ValueError(f'Unexpected export permission for {key}: {permission!r}')
    for key, exported in REQUIRED.items():
        if seen.get(key) is not exported:
            raise ValueError(f'Missing component or wrong exported state: {key}')
    return seen
