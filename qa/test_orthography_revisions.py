"""Check the reviewed defaults and actual bundled word forms."""
import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from han_orthography import Orthography
from import_northern_words import han_units


def main():
    table = Orthography.from_file()
    rows = table.rows
    assert len(rows) == 308
    for base, row in rows.items():
        assert row['t1'] == row['t4'], base
        if base.endswith(('an', 'ang')):
            assert all(len(row['t' + str(i)]) == 1 for i in range(1, 5)), base
        if base.endswith('ang') and base[:-1] in rows:
            assert all(row['t' + str(i)] == rows[base[:-1]]['t' + str(i)] for i in range(1, 5)), base
        if base.startswith('q'):
            assert all(not row['t' + str(i)].startswith('欺') for i in range(1, 5)), base
    for base, tone, wanted in [('ei', 1, '诶'), ('hxi', 1, '诶'), ('ma', 1, '吗'), ('ma', 2, '祃'),
                               ('lei', 2, '累'), ('duan', 2, '段'), ('yan', 1, '央'),
                               ('cei', 1, '此'), ('cao', 1, '超'), ('zuai', 1, '拽'),
                               ('qia', 1, '起鸦'), ('qio', 1, '起哟'), ('ha', 2, '下'), ('xia', 2, '夏')]:
        assert rows[base]['t' + str(tone)] == wanted, base
    assert [rows['ei']['t' + str(i)] for i in range(1, 5)] == [rows['hxi']['t' + str(i)] for i in range(1, 5)]
    asset = ROOT / 'shared/bundle/Assets/words.tsv'
    with asset.open() as f:
        reader = csv.DictReader(f, delimiter='\t')
        fields = reader.fieldnames
        records = list(reader)
    by_id = {r['id']: r for r in records if r['kind'] != 'zh'}
    for ident, value in [('w439', '尺'), ('w440', '尺'), ('w441', '尺'),
                         ('w954', '挤'), ('w955', '几'), ('w1333', '里'), ('w1334', '理')]:
        assert by_id[ident]['recommended'] == value, ident
    for base, row in rows.items():
        for i, suffix in enumerate('xfrv', 1):
            actual = by_id['ortho-' + base + suffix]
            assert actual['recommended'] == actual['hanUnits'] == row['t' + str(i)], (base, suffix)
    count = 0
    with (ROOT / 'shared/bundle/Assets/northern-words.tsv').open() as f:
        for record in csv.DictReader(f, delimiter='\t', fieldnames=fields):
            if record['kind'] != 'tujia':
                continue
            compact = ''.join(c for c in record['word'] if c not in " -'’")
            expected = han_units(compact, table)
            assert expected and record['hanUnits'] == '|'.join(expected), record['id']
            assert record['recommended'] == ''.join(expected), record['id']
            count += 1
    print(f'PASS: 308 defaults, reviewed lexemes and {count} additional northern entries')


if __name__ == '__main__':
    main()
