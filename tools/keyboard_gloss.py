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
    '我们（第一人称复数）': '我们', '你们（第二人称复数）': '你们',
    '您': '你', '讨（讨饭）': '讨', '讨(讨饭)': '讨',
    '小孩儿': '小孩', '儿童': '小孩', '孩童': '小孩', '孩子': '小孩',
}

# Explicit equivalences, used on complete glosses or on a list whose members
# all denote the same sense. Multi-sense lists remain intact.
SYNONYMS = {
    alias: canonical
    for canonical, aliases in {
        '拿': ('拿', '取'), '石头': ('石', '石头', '岩石'),
        '茶': ('茶', '茶水'), '喜欢': ('爱', '喜欢'),
        '姐姐': ('姐', '姐姐'), '弟弟': ('弟', '弟弟'),
        '那样': ('那样', '如此'), '学习': ('学', '学习'),
        '奶奶': ('奶奶', '祖母'), '外婆': ('外婆', '外祖母'),
        '新': ('新', '新的'), '换': ('换', '更换'),
        '修剪': ('剪', '修剪'), '抱': ('抱', '拥抱'),
        '墙': ('墙', '墙壁'), '嘴': ('嘴', '嘴巴'),
        '沙': ('沙', '沙子'), '泥': ('泥', '泥土'),
        '小孩': ('小孩', '孩子', '小孩儿', '儿童', '孩童'),
        '唱': ('唱', '唱歌'), '士兵': ('兵', '士兵'),
        '表扬': ('表扬', '赞扬'), '抓': ('抓', '捉'),
        '移动': ('移动', '挪动'), '抚摸': ('摸', '抚摸'),
        '哭': ('哭', '哭泣'), '跑': ('跑', '奔跑'),
        '高兴': ('高兴', '开心'), '生气': ('生气', '发怒'),
        '看': ('看', '观看'), '听': ('听', '听见'),
        '睡觉': ('睡觉', '睡'), '走': ('走', '行走'),
        '说': ('说', '说话'), '水井': ('井', '水井'),
        '鼻涕': ('鼻涕', '鼻液'), '口水': ('口水', '唾沫'),
    }.items() for alias in aliases
}

def normalize_meaning(value):
    value = value.strip()
    if value in EQUIVALENTS:
        return EQUIVALENTS[value]
    parts = [part.strip() for part in re.split(r'[、，,；;/／]', value)]
    mapped = [SYNONYMS.get(part, part) for part in parts]
    return mapped[0] if mapped and len(set(mapped)) == 1 else value

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
    preferred = {'bifzixkar': '秘兹佧', 'mirjirkar': '密及佧', 'mifjixkax': '密及佧'}
    if row['meaning'] == '土家族' and row['word'] in preferred and row.get('recommended'):
        old = row['recommended']
        han = preferred[row['word']]
        if old != han:
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
