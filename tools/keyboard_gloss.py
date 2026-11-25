"""Short, explicit display equivalents and sense-specific character choices."""
import re

EQUIVALENTS = {
    '我（第一人称单数）': '我', '我(第一人称单数)': '我', '第一人称单数': '我',
    '你（第二人称单数）': '你', '你(第二人称单数)': '你', '第二人称单数': '你',
    '他／她／它（第三人称单数）': '他', '他；她': '他', '他/她': '他',
    '搜': '找', '寻找': '找', '搜寻': '找', '寻找；找到': '找',
    '藏东西': '藏', '收藏': '藏',
    '真的；真实的；真；真实': '真', '真的': '真', '真实的': '真', '真实': '真',
    '他自己／她自己／它自己': '他自己',
    '您': '你', '讨（讨饭）': '讨', '讨(讨饭)': '讨',
    '小孩儿': '小孩', '儿童': '小孩', '孩童': '小孩', '孩子': '小孩',
}

def normalize_meaning(value):
    return EQUIVALENTS.get(value.strip(), value.strip())

def short_gloss(value):
    value = normalize_meaning(value)
    value = re.sub(r'[（(](?:原文|原义|原注|原英文|原英释|原释义|第一人称|第二人称|第三人称|具体[^）)]*待核|[^）)]*须核原文)[^）)]*[）)]', '', value).strip()
    return EQUIVALENTS.get(value, value)

def update_row(row):
    if row.get('kind') not in ('tujia', 'phonetic'):
        return row
    if row['word'] == 'bifzixkar' and row['meaning'] == '自称；族群自称':
        row['meaning'] = row['shortGloss'] = '土家族'
    row['meaning'] = normalize_meaning(row['meaning'])
    row['shortGloss'] = short_gloss(row.get('shortGloss') or row['meaning'])
    preferred = {'bifzixkar': '秘兹佧', 'mirjirkar': '泌基佧', 'mifjixkax': '泌基佧'}
    if row['meaning'] == '土家族' and row['word'] in preferred and row.get('recommended'):
        old = row['recommended']
        han = preferred[row['word']]
        row['aliases'] = ' '.join(dict.fromkeys([*row.get('aliases', '').split(), old]))
        row['recommended'], row['hanUnits'] = han, '|'.join(han)
    if row['word'] in ('nif', 'gof'):
        wanted = {('nif', '你'): '伲', ('gof', '他'): '个'}.get((row['word'], row['meaning']))
        if wanted and row.get('recommended'):
            old = row['recommended']
            aliases = row.get('aliases', '').split()
            if old != wanted:
                aliases.append(old)
            row['aliases'] = ' '.join(dict.fromkeys(aliases))
            row['recommended'] = row['hanUnits'] = wanted
    return row


def ethnonym_variants(row):
    """Offer the user's reviewed alternate spellings as separate candidates."""
    from copy import deepcopy
    if row.get('meaning') != '土家族' or row.get('kind') != 'tujia':
        return []
    word = row['word']
    forms = []
    if word == 'bifzixkar':
        forms.append(('alt', '币子佧', word, row['plain'], row['diacritic']))
        forms.extend([('primary', '秘基佧', 'bifjixkar', 'bijika', 'bíjīkǎ'),
                      ('alt', '币基佧', 'bifjixkar', 'bijika', 'bíjīkǎ')])
    elif word in ('mirjirkar', 'mifjixkax'):
        forms.append(('alt', '秘基佧', word, row['plain'], row['diacritic']))
    for variant, han, ye, plain, accent in forms:
        copy = deepcopy(row)
        copy.update(id='ethnonym-'+variant+'-'+row['id']+'-'+plain, word=ye, plain=plain,
                    diacritic=accent, key='', recommended=han, hanUnits='|'.join(han),
                    aliases='', variants='', frequency=0, romanAliases='', presentation='')
        yield copy
