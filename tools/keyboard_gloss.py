"""Short, explicit display equivalents and sense-specific character choices."""
import re

EQUIVALENTS = {
    '很多': '多', '多；很多': '多', '多、很多': '多',
    '男性生殖器': '男生殖器', '阴茎': '男生殖器',
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

# Source-specific survey wording frequently expresses the same sense in
# different ways. These aliases affect the on-keyboard label only; full
# dictionary meanings and unrelated senses remain available.
SHORT_ALIASES = {
    alias: canonical
    for canonical, aliases in {
        '多': ('多', '很多', '大量'),
        '男生殖器': ('男生殖器', '男性生殖器', '阴茎'),
        '女生殖器': ('女生殖器', '女性生殖器'),
        '父亲': ('父亲', '爸爸', '爸'), '母亲': ('母亲', '妈妈', '妈'),
        '小': ('小', '小的'), '大': ('大', '大的'),
        '那': ('那', '那个'), '这': ('这', '这个'),
        '弟弟': ('弟弟', '弟'), '姐姐': ('姐姐', '姐'),
        '背部': ('背部', '脊背'), '房子': ('房子', '房屋'),
        '春天': ('春天', '春'), '家': ('家', '家里'),
        '一半': ('一半', '半'), '煮': ('煮', '烹调'),
        '扔': ('扔', '抛', '掷'), '关': ('关', '关闭', '合上'),
        '缝': ('缝', '缝合'), '路': ('路', '道路'),
        '老鹰': ('老鹰', '鹰类'), '喜欢': ('喜欢', '爱'),
        '活': ('活', '活着'), '使用': ('使用', '用'),
        '拿': ('拿', '取', '拿取'), '怕': ('怕', '害怕'),
        '去': ('去', '离开'), '远': ('远', '遥远'),
        '叫': ('叫', '叫喊', '喊', '喊叫', '喊出声'),
        '追': ('追', '追赶'), '驱逐': ('驱逐', '赶走'),
        '救': ('救', '救助', '营救'), '木头': ('木头', '木料'),
        '哪里': ('哪里', '哪'), '秋天': ('秋天', '秋'),
        '弓': ('弓', '弓箭'), '刀': ('刀', '刀子'),
        '宽': ('宽', '宽敞', '宽阔'), '清澈': ('清澈', '清澈（水）'),
        '近': ('近', '接近'), '轻': ('轻', '轻（重量）', '不重'),
        '漂亮': ('漂亮', '美丽', '好看'), '鸭': ('鸭', '鸭子'),
        '睡觉': ('睡觉', '睡', '睡着', '入睡'), '笑': ('笑', '微笑'),
        '醉': ('醉', '喝醉'), '血': ('血', '血液'), '银': ('银', '银子'),
        '雨伞': ('雨伞', '伞'), '庙': ('庙', '寺庙'),
        '年老': ('年老', '老（人）'), '明亮': ('明亮', '亮'),
        '腐烂': ('腐烂', '烂'), '听不见': ('听不见', '聋'),
        '捡': ('捡', '捡起'), '回答': ('回答', '答复', '答'),
        '同意': ('同意', '答应'), '等待': ('等待', '等', '等候'),
        '挑选': ('挑选', '选择'), '切': ('切', '切菜', '切蔬菜', '切（切菜）'),
        '到': ('到', '到达'), '躲': ('躲', '躲藏'), '逃': ('逃', '逃跑'),
        '想': ('想', '想念'), '指': ('指', '指向', '指出'),
        '捆': ('捆', '绑', '系'), '生育': ('生育', '生孩子'),
        '吐': ('吐', '呕', '呕吐'), '补': ('补', '修补'),
        '骗': ('骗人', '骗', '欺骗', '诈骗'),
        '唱': ('唱', '唱歌'), '说': ('说', '说话', '讲话'),
        '烟': ('烟', '香烟'),
        '袋子': ('袋子', '口袋'), '绳子': ('绳子', '绳'),
        '臭': ('臭', '臭的', '发臭'),
        '鼻涕': ('鼻涕', '鼻液'), '口水': ('口水', '唾沫'),
        '树': ('树', '树木'),
        '笑': ('笑', '微笑'), '跑': ('跑', '奔跑'),
        '月': ('月', '一个月'), '年': ('年', '一年'),
        '变': ('变', '改变', '变化'), '边': ('边', '边缘'),
        '埋': ('埋', '埋东西'), '钉': ('钉', '钉钉子'),
        '挂': ('挂', '悬挂'), '戴': ('戴', '戴头巾', '戴帽子'),
        '挖': ('挖', '挖洞', '挖掘'), '剁': ('剁', '剁肉'),
        '毒': ('毒', '毒素', '毒药'), '堆': ('堆', '堆放', '堆积'),
        '重': ('重', '沉重'), '管': ('管', '管理'),
        '靠': ('靠', '依靠'), '骑': ('骑', '骑马'),
        '柴': ('柴', '柴火'), '输': ('输', '失败'), '守': ('守', '看守'),
        '梁': ('梁', '房梁'), '炮': ('炮', '大炮'),
        '袍': ('袍', '长袍'), '阉': ('阉', '阉公鸡', '骟'),
        '犁': ('犁', '犁地', '耕地'), '撞': ('撞', '碰撞'),
        '沸': ('沸', '沸腾', '水沸腾', '水开'),
        '摘': ('摘', '采摘'), '浮': ('浮', '漂浮'),
        '围': ('围', '包围'), '饭': ('饭', '米饭'),
        '城': ('城', '城市'), '孵': ('孵', '孵蛋', '孵化'),
        '拧': ('拧', '拧毛巾'), '煮': ('煮', '煮肉', '烹调'),
        '装': ('装', '装满'), '病': ('病', '生病'),
        '疼': ('疼', '痛', '疼痛'), '冻': ('冻', '冻结'),
        '填': ('填', '填充'), '吹': ('吹', '用嘴吹'),
        '算': ('算', '计算'), '铺': ('铺', '铺开', '铺设'),
        '砍': ('砍', '砍树', '砍断'), '落': ('落', '落下'),
        '拔': ('拔', '拔出'), '治': ('治', '医治', '治疗'),
        '撕': ('撕', '撕开'), '剖': ('剖', '剖开'),
        '排水': ('排水', '放水'), '溢': ('溢', '溢出'),
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
    if value == '看守；观看；表（原义未细分）': return '看；守'
    if value == 'to raise; 生/养': return '养'
    value = re.sub(r'[（(](?:原文|原义|原注|原英文|原英释|原释义|第一人称|第二人称|第三人称|具体[^）)]*待核|动词后小词|名词后小词|动词后|[^）)]*须核原文)[^）)]*[）)]', '', value).strip()
    if '量词' in value and len(value) > 6:
        return value
    parts, current, depth = [], [], 0
    for char in value:
        if char in '（(': depth += 1
        if char in '）)' and depth: depth -= 1
        if char in '、，,；;/／' and not depth:
            if ''.join(current).strip(): parts.append(''.join(current).strip())
            current = []
        else:
            current.append(char)
    if ''.join(current).strip(): parts.append(''.join(current).strip())
    parts = [SHORT_ALIASES.get(part, SYNONYMS.get(part, EQUIVALENTS.get(part, part))) for part in parts]
    return '；'.join(dict.fromkeys(parts))

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
