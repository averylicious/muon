"""Conservative, dependency-free selection of documentation-only push checks."""
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess


ROOT_DOCS = {'README.md', 'AGENTS.md', 'CHANGELOG.md', 'CONTRIBUTING.md', 'LICENSE'}
DOC_SUFFIXES = {'.md', '.txt', '.png', '.jpg', '.jpeg', '.svg', '.webp', '.gif', '.pdf'}
SHA = re.compile(r'[0-9a-f]{40}')


def is_documentation(path):
    p = PurePosixPath(path)
    return (path in ROOT_DOCS or path == '.github/pull_request_template.md'
            or (p.parts[0] == 'docs' and p.suffix.lower() in DOC_SUFFIXES))


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)


def changed_paths(base, head):
    # No rename detection: moving app code into docs must still count as code deletion.
    return set(git('diff', '--name-only', '--no-renames', '-z', base, head, '--')
               .decode('utf-8').rstrip('\0').split('\0')) - {''}


def classify(event_name, ref, head, event):
    """Return (build_apks, reason, changed paths). Unknown history builds, never skips."""
    if event_name != 'push' or not ref.startswith('refs/heads/'):
        return True, 'Manual, tag or non-branch event: full build.', set()
    if not SHA.fullmatch(head):
        return True, 'Unknown head commit: full build.', set()
    try:
        paths = set()
        before = event.get('before', '')
        if before and before != '0' * 40:
            if not SHA.fullmatch(before):
                raise ValueError('Invalid before SHA')
            paths.update(changed_paths(before, head))
        elif ref == 'refs/heads/main':
            raise ValueError('No previous main commit')
        if ref != 'refs/heads/main':
            # Include all unmerged feature work even when the last push only edits docs.
            base = git('merge-base', head, 'refs/remotes/origin/main').decode().strip()
            paths.update(changed_paths(base, head))
        if paths and all(is_documentation(p) for p in paths):
            return False, 'Only documentation paths changed.', paths
        return True, 'Code/build/unknown paths or empty comparison: full build.', paths
    except (subprocess.CalledProcessError, UnicodeError, ValueError, OSError):
        return True, 'Comparison unavailable: full build.', set()


def check_documents(paths):
    """Cheap checks for changed prose, without Java, SDKs or third-party packages."""
    for name in sorted(paths):
        path = Path(name)
        if path.suffix.lower() not in {'.md', '.txt'} or not path.is_file():
            continue  # Deleted files and binary design assets need no prose check.
        text = path.read_text(encoding='utf-8')
        if '\0' in text or re.search(r'^(?:<{7}|={7}|>{7})(?: |$)', text, re.MULTILINE):
            raise ValueError(f'Invalid text or unresolved merge marker: {name}')
        # GitHub-style backtick and tilde fences; closing fences may be longer.
        fence = None
        for line in text.splitlines():
            match = re.match(r'^ {0,3}(`{3,}|~{3,})(.*)$', line)
            if not match:
                continue
            marks, rest = match.groups()
            if fence is None:
                fence = marks
            elif marks[0] == fence[0] and len(marks) >= len(fence) and not rest.strip():
                fence = None
        if fence:
            raise ValueError(f'Unclosed Markdown fence: {name}')


def main():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    build, reason, paths = classify(os.environ['GITHUB_EVENT_NAME'],
                                    os.environ['GITHUB_REF'], os.environ['GITHUB_SHA'], event)
    print(reason)
    check_documents(p for p in paths if is_documentation(p))
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'build_apks={str(build).lower()}\n')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write(f'## CI scope\n\n{reason}\n\n')
        summary.write('Full signed Android build selected.\n' if build else
                      'Documentation checks passed. No SDK, signing secrets, APK artifacts or release for this run.\n')


if __name__ == '__main__':
    main()
