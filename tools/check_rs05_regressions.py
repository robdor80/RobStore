#!/usr/bin/env python3
"""Preserve every passing audited P01/P03 and RS-04 case in both Android variants."""
from pathlib import Path
import xml.etree.ElementTree as ET

for variant in ('debug', 'release'):
    passing = set()
    total = failures = 0
    for path in Path(f'app/build/test-results/test{variant.title()}UnitTest').glob('TEST-*.xml'):
        for case in ET.parse(path).getroot().findall('testcase'):
            total += 1
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                failures += 1
            else:
                passing.add((case.attrib['classname'], case.attrib['name']))
    for phase in ('rs04', 'rs05-p01', 'rs05-p03'):
        baseline = {tuple(line.split('\t')[:2]) for line in
                    Path(f'docs/evidencias/{phase}/tests-{variant}.tsv').read_text().splitlines()[1:]}
        if baseline - passing:
            raise SystemExit(f'{variant}: audited {phase} cases missing/failing: {sorted(baseline - passing)}')
        print(f'{variant}: {phase} {len(baseline)}/{len(baseline)} retained')
    if failures or total < 254:
        raise SystemExit(f'{variant}: unexpected test totals: {total}, unsuccessful: {failures}')
    print(f'{variant}: {total} passed, none failed/skipped')
