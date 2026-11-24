"""Align the archived TLP transcription to the keyboard's northern display forms."""
import json
import re
import subprocess
from collections import OrderedDict
from xu_song import plain

# Unambiguous spelling correspondences for this Longshan transcription. The
# source distinguishes the syllable wũ²¹ from the words around it: its meaning
# depends on which complete dictionary entry and source segment we align.
# Retain original forms for input, and retain the TLP IPA for each source word.
GLIDES = {'yiuf':'youf','yior':'yor','yiar':'yar','yiaox':'yaox','yianx':'yanx','yiev':'yev','wuar':'war'}
SIT_ID = 'w1658'
TEA_ID = 'stedt-rn-368307'
DRINK_ID = 'w874'

# In LS-60 paragraph 5 the vocative ar has its own syllable after gox gov.
# The source glosses include the Chinese particles of the utterance in the noun.
BROTHER_GLOSSES = {('gox gov', '哥哥啊'): '哥哥', ('ar', '哥哥'): '啊',
                  ('gox gox', '哥哥呢'): '哥哥'}

def reviewed_glosses(block):
    corrected=[]
    brother_paragraph = block['ye'].casefold().startswith('air gox gov ar, ar kov ')
    for gloss in block['glosses']:
        ye=gloss['ye'].lower()
        reviewed = BROTHER_GLOSSES.get((ye, gloss['zh'])) if brother_paragraph else None
        if reviewed is not None:
            corrected.append(dict(gloss, zh=reviewed, sourceGloss=gloss['zh']))
        elif ye=='gav dunr' and gloss['zh']=='一会儿坐':
            corrected.append(dict(ye=ye,zh='一会儿'))
        elif ye=='wunr' and gloss['zh'] in ('茶','坐'):
            corrected.append(dict(ye=ye,zh='坐'))
        elif ye in ('ar ceix hur','ar ceir hur') and gloss['zh'] in ('喝','茶喝'):
            corrected.extend([dict(ye=' '.join(ye.split()[:2]),zh='茶'),dict(ye='hur',zh='喝')])
        else:
            corrected.append(gloss)
    return corrected

def align(words, base, site, project, orthography, refs):
    import gzip
    with gzip.open(site/'dictionary.json.gz','rt') as f:
        dictionary=json.load(f)['words']
    witnesses={SIT_ID,TEA_ID,DRINK_ID,'stedt-rn-288978'}
    req=dict(entries=[w for w in dictionary if w['id'] in witnesses],
             words=list(dict.fromkeys([w['word'] for w in words.values()])),
             ipa=list(dict.fromkeys(p for w in words.values() for p in w['syllables'])))
    convert=lambda payload: json.loads(subprocess.check_output(['node',str(project/'tools/site_spellings.cjs'),str(site)],input=json.dumps(payload).encode()))
    result=convert(req)
    for w in words.values():
        raw=w['word']; sense=w['storyMeaning']; parts=w['sourceYe'].split()
        w['siteSyllables']=parts
        w['sourceWord']=raw
        target=SIT_ID if raw=='wunr' and sense=='坐' else TEA_ID if raw in ('arceix','arceir') and sense=='茶' else DRINK_ID if raw=='hur' and sense=='喝' else None
        if target:
            options=result['display'][target]
            assert len(options['ye'])==len(options['accent'])==1,(target, options)
            w['word']=options['ye'][0]; w['id']=target; w['sourceEntry']=target
            w['siteSyllables']=['ongr'] if target==SIT_ID else ['ar','ciex'] if target==TEA_ID else ['hur']
            existing=next((r for r in base if r['id']==target),None)
            if existing:
                w['inBase']=True
                w['hanUnits']=existing['hanUnits'];w['recommended']=existing['recommended']
            else:
                normalized_parts=['ar','ciex'] if target==TEA_ID else ['hur']
                witness_ipa=['a²¹','tsʰie⁵⁵'] if target==TEA_ID else ['xu²¹']
                w['hanUnits']=orthography.units(normalized_parts,[refs[p]['first']['h'] for p in witness_ipa])
                w['recommended']=''.join(w['hanUnits'])
            if target==SIT_ID: w['alternateWitnesses']=result['display']['stedt-rn-288978']['ye']+result['display']['stedt-rn-288978']['accent']+['wong']
        elif not w['inBase']:
            # The site labels TLP's original spellings explicitly. Only convert
            # equivalent source notations that can be checked against the site.
            normalized=[]
            for spelling,ipa in zip(parts,w['syllables']):
                if 'ũ' in ipa:
                    converted=result['spellings'][ipa]
                    assert re.fullmatch('[a-z]+[xfrv]',converted),(ipa,converted)
                    normalized.append(converted)
                else: normalized.append(GLIDES.get(spelling,spelling))
            w['word']=''.join(normalized)
            w['siteSyllables']=normalized
        original_accent=result['accented'][raw]
        if raw!=w['word']:
            w['romanAliases']=list(dict.fromkeys([raw,plain(original_accent),original_accent]))
        w.setdefault('romanAliases',[]).extend(w.pop('alternateWitnesses',[]))
    accents=convert({'words':[w['word'] for w in words.values()]})['accented']
    for w in words.values():
        w['diacritic']=accents[w['word']];w['plain']=plain(w['diacritic']);w['key']=w['plain']
    # Merge aliases from multiple occurrences/tones of one dictionary entry, e.g. 茶.
    merged=OrderedDict()
    for w in words.values():
        slot=merged.setdefault(w['id'], [])
        for a in w['romanAliases']:
            if a not in slot:slot.append(a)
    for w in words.values():w['romanAliases']=merged[w['id']]
    return merged
