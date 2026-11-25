"""Read the four-tone reference and apply it per syllable, before lexical exceptions."""
import csv
import json
from pathlib import Path

DATA = Path(__file__).resolve().parent / 'data'
REVISIONS = json.loads((DATA/'orthography-revisions.json').read_text(encoding='utf-8'))
with (DATA/'plain-han-loans.tsv').open(encoding='utf-8') as stream:
    PLAIN_HAN_LOANS = {r['id']: r for r in csv.DictReader(stream, delimiter='\t')}
with (DATA/'core-han-loans.tsv').open(encoding='utf-8') as stream:
    for row in csv.DictReader(stream, delimiter='\t'):
        PLAIN_HAN_LOANS.setdefault(row['id'], row)
APICAL = frozenset(REVISIONS['apical']['bases'])
TONES = 'xfrv'
# The Longshan text retains explicit glides in these transcriptions. Its source
# converter uses y-/w- and interprets nasal u as ong. Do not map en to ian.
SOURCE_VARIANTS = {'yia':'ya', 'yian':'yan', 'yiao':'yao', 'yie':'ye', 'yio':'yo',
                   'yiu':'you', 'wua':'wa', 'mun':'mong', 'wun':'weng'}


class Orthography:
    def __init__(self, rows):
        self.rows = {r['base']: r for r in rows}
        if len(self.rows) != len(rows):
            raise ValueError('Duplicate orthographic syllable')
        for base in APICAL:
            if base in self.rows and any(len(self.rows[base]['t'+str(t)]) != 1 for t in range(1, 5)):
                raise ValueError(f'Apical syllable must use one Han character in every tone: {base}')
        self.fallback = set()

    @classmethod
    def from_file(cls, path=DATA/'orthography-defaults.tsv'):
        with Path(path).open(encoding='utf-8') as stream:
            return cls(list(csv.DictReader(stream, delimiter='\t')))

    def base(self, syllable):
        if len(syllable) < 2 or syllable[-1] not in TONES:
            return None
        raw = syllable[:-1]
        if raw in self.rows:
            return raw
        alt = SOURCE_VARIANTS.get(raw)
        if alt in self.rows:
            return alt
        if raw.endswith('ang') and raw[:-1] in self.rows:
            return raw[:-1]
        if raw.endswith('an') and raw+'g' in self.rows:
            return raw+'g'
        return None

    def units(self, parts, old_units, *, override=None):
        if len(parts) != len(old_units):
            raise ValueError(f'Expected one legacy unit for each of {parts}: {old_units}')
        if override is not None:
            if len(override) != len(parts) or not all(override):
                raise ValueError(f'Invalid exception for {parts}: {override}')
            for part, unit in zip(parts, override):
                if self.base(part) in APICAL and len(unit) != 1:
                    raise ValueError(f'Apical syllable {part} cannot take a multi-character unit {unit}')
            return list(override)
        result = []
        for part, old in zip(parts, old_units):
            key = self.base(part)
            if key is None:
                self.fallback.add((part, old))
                result.append(old)
            else:
                result.append(self.rows[key]['t'+str(TONES.index(part[-1])+1)])
        return result
