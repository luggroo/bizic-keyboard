"""Update the trial word list with the four-tone table and established sense spellings."""
import argparse
import csv
import json
from pathlib import Path
from shutil import copyfile
from han_orthography import DATA, REVISIONS, Orthography


def dump(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('web', type=Path)
    args = cli.parse_args()
    web = args.web
    table = Orthography.from_file()
    original, variants = (web/'words.js').read_text().split(';\nconst soundVariants=', 1)
    entries = json.loads(original.split('=',1)[1]); sounds = json.loads(variants.rstrip().rstrip(';'))
    refs = {r['ipa']: r['spelling'] for r in json.loads((web/'reference-data.json').read_text())}
    exceptions = []
    for e in entries:
        parts = [refs[ipa] for ipa in e['syllables']]
        if ''.join(parts) != e['word']:
            raise ValueError(f"Missing syllable alignment for {e['word']}")
        old = e.setdefault('legacyHanUnits',list(e['recommended']))
        default = table.units(parts, old)
        # Explicit user preferences and the previous sense-specific choices are
        # keyed to lexemes. An unrelated homophone never inherits their Han.
        keep = (e.get('meaningForm') or (len(parts)==1 and parts[0][:-1] in {'gi','ki','hi','hxi'})
                or e['id'] in {'w684','w687','w2548'})
        revision = REVISIONS['lexemes'].get(e['id'])
        if revision:
            if revision['meaning'] != e['meaning'] or revision['parts'] != parts:
                raise ValueError(f"Misaligned orthography revision {e['id']}")
            e['etymology'] = revision['etymology']
            e['etymologySource'] = revision['source']
            e['meaningForm'] = ''.join(revision['hanUnits'])
            e['formNote'] = revision['formNote']
            keep = True
        # Lexical decisions use their reviewed units, which may be newer than
        # legacyHanUnits. The latter records historical forms for input aliases.
        reviewed = e.get('hanUnits', old) if e.get('meaningForm') else old
        units = table.units(parts, old, override=revision['hanUnits'] if revision else reviewed if keep else None)
        previous = ''.join(old)
        e['hanUnits'] = units; e['recommended'] = ''.join(units)
        for lookup in [previous, REVISIONS['previousWordForms'].get(e['id'])]:
            if lookup and lookup != e['recommended'] and lookup not in e['aliases']:
                e['aliases'].append(lookup)
        if e['id'] in REVISIONS['previousWordForms']:
            e['aliases'] = [a for a in e['aliases'] if a != e['recommended']]
        if keep:
            exceptions.append(dict(id=e['id'],word=e['word'],meaning=e['meaning'],han=e['recommended'],
                base=[table.base(x) or x[:-1] for x in parts],units=units,defaultUnits=default))
            if revision:
                exceptions[-1].update(etymology=e['etymology'],etymologySource=e['etymologySource'],formNote=e['formNote'])
    (web/'words.js').write_text('const entries='+dump(entries)+';\nconst soundVariants='+dump(sounds)+';\n')
    with (DATA/'orthography-grammar.tsv').open() as f:
        grammar=list(csv.DictReader(f,delimiter='\t'))
    data = dict(rows=[dict(base=k,tones=[r['t'+str(i)] for i in range(1,5)],review=r['review']) for k,r in table.rows.items()],
                exceptions=exceptions,grammar=grammar,apical=REVISIONS['apical'],
                fallback=[dict(syllable=k,form=v)for k,v in sorted(table.fallback)])
    (web/'orthography-data.js').write_text('window.tujiaOrthography='+dump(data)+';\n')
    copyfile(DATA/'orthography-defaults.tsv',web/'orthography-defaults.tsv')
    print(len(table.rows),'syllables;',len(exceptions),'retained lexical decisions;',len(entries),'base entries;',sorted(table.fallback))


if __name__=='__main__':
    main()
