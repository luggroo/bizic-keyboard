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
    for base, tone, wanted in [('nga', 2, '牙'), ('ei', 1, '诶'), ('hxi', 1, '诶'), ('ma', 1, '吗'), ('ma', 2, '祃'),
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
    with (ROOT / 'tools/data/northern-han-loans.tsv').open() as f:
        loans = {(r['id'], r['ye']): r for r in csv.DictReader(f, delimiter='\t')}
    seen_loans = set()
    with (ROOT / 'shared/bundle/Assets/northern-words.tsv').open() as f:
        for record in csv.DictReader(f, delimiter='\t', fieldnames=fields):
            if record['kind'] != 'tujia':
                continue
            compact = ''.join(c for c in record['word'] if c not in " -'’")
            expected = han_units(compact, table)
            if record['id'].startswith(('yubao-', 'ethnonym-')) or (record['word'] == 'bifzixkar' and record['meaning'] == '土家族'):
                assert record['recommended'] == ''.join(record['hanUnits'].split('|')), record['id']
                continue
            if (record['word'], record['meaning']) in (('nif', '你'), ('gof', '他')):
                expected = ['伲' if record['word'] == 'nif' else '个']
            loan = loans.get((record['id'], record['word']))
            if loan:
                seen_loans.add((record['id'], record['word']))
                assert loan['meaning'] == record['meaning'], record['id']
                expected = list(loan['han'])
                assert record['presentation'] == 'plainHanLoan', record['id']
            assert expected and record['hanUnits'] == '|'.join(expected), record['id']
            assert record['recommended'] == ''.join(expected), record['id']
            count += 1
    assert seen_loans == set(loans), 'Supplementary loan forms must match existing entries'
    for ident in ('w810', 'w3682', 'w3695', 'stedt-rn-288910', 'stedt-rn-206778'):
        assert any(key[0] == ident and row['han'] == '国家' for key, row in loans.items()), ident
    print(f'PASS: 308 defaults, reviewed lexemes and {count} additional northern entries')


if __name__ == '__main__':
    main()
