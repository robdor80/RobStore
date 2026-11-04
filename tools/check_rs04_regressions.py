#!/usr/bin/env python3
"""Require every audited RS-03 test case to remain present and passing in both variants."""
from pathlib import Path
import xml.etree.ElementTree as ET

for variant in ('debug', 'release'):
    baseline = {tuple(line.split('\t')[:2]) for line in
                Path(f'docs/evidencias/rs03/tests-{variant}.tsv').read_text().splitlines()[1:]}
    passing = set()
    for path in Path(f'app/build/test-results/test{variant.title()}UnitTest').glob('TEST-*.xml'):
        for case in ET.parse(path).getroot().findall('testcase'):
            if not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                passing.add((case.attrib['classname'], case.attrib['name']))
    missing = baseline - passing
    if missing:
        raise SystemExit(f'{variant}: RS-03 cases missing or not passing: {sorted(missing)}')
    if len(baseline) != 99:
        raise SystemExit('Unexpected audited RS-03 baseline')
    print(f'{variant}: 99/99 audited RS-03 cases retained and passing')
