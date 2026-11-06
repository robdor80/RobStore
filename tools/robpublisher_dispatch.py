#!/usr/bin/env python3
"""Minimal RobPublisher adapter: invoke once from the Publish button."""
import argparse
import json
import re
import subprocess


def dispatch(repository, channel='release'):
    if not re.fullmatch(r'robdor80/[A-Za-z0-9_.-]+', repository) or channel not in ('release', 'dev'):
        raise ValueError('Invalid owner repository or channel')
    # Use RobPublisher's existing GitHub authentication. Never retry a dispatch:
    # a lost response might mean that GitHub already accepted the publication.
    payload = dict(ref='main', inputs=dict(channel=channel))
    result = subprocess.run([
        'gh', 'api', '--method', 'POST',
        f'repos/{repository}/actions/workflows/android-distribute.yml/dispatches',
        '--input', '-'], input=json.dumps(payload), text=True, capture_output=True)
    if result.returncode:
        raise ValueError('GitHub did not confirm the order; check its status before retrying')
    return dict(repository=repository, channel=channel, status='accepted')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--channel', choices=('release', 'dev'), default='release')
    args = parser.parse_args()
    print(json.dumps(dispatch(args.repository, args.channel)))


if __name__ == '__main__':
    main()
