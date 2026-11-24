# 预测音节的汉义依据

在 `kǎ` 后，候选 `mǒng`、`mǔn` 都只显示读音。两者可以按现行记音字表写成「萌」。拼写输出保留各自的韵母。下面的三个例子说明何时能在音节上方显示汉义。

| 预测与上下文 | 词典或原文记载 | 候选卡 |
| --- | --- | --- |
| `kǎ` 后的 `mǒng`（`mongr`） | Brassett 的 **`kǎmǒng` 整词**释为「树」。`tōngmǒng` 是「呻吟」，`tòngmǒng` 是「线」。中国语保龙山第 2498 项单列 `/muŋ²¹/`，调查词「午」带有「属马」提示，前后调查项是「巳」「未」。[树](https://tujia.biz/?id=w1102) · [呻吟](https://tujia.biz/?id=w2302) · [线](https://tujia.biz/?id=w2303) · [龙山「午」](https://tujia.biz/?id=yubao-60757-v-2498) | 原始音节的汉义留空。「午」用于这一地支、生肖语境。「树」可显示在完整词条 `kǎmǒng` 上。 |
| `kǎ` 后的 `mǔn`（`munr`） | 龙山 LS-43 的 `Jif gav kar munr ...` 谜面有「绿绿的树」；LS-48 的 `Kar munr jief ...` 谜面有「有树不见动」。两处都记 `/kʰa²¹ mũ²¹/`，都提供整句汉译。这两个文本没有逐词释义。此次完整北部词典检索未找到以 `munr` 单独立目的完整义项。[LS-43 辣椒谜](https://tujia.biz/?id=tlp-longshan-proverbs-28) · [LS-48 画谜](https://tujia.biz/?id=tlp-longshan-proverbs-33) | 音节的汉义留空。在手稿、词表和拼写输出里保留该调查材料的 `mǔn`。 |
| 句首预测 `jìn`（`jinv`） | `w1051` 释为「紧」，英文 *to be urgent*，英语表 `w3991` 也作 *urgent*。两项都能核回这一读音，检查范围内未见其他独立汉义、黏着形式或含它的不同复音词记录。句首到 `jinv` 出现在原始 1,000 对音节排名第 554 位；该名次不表示词频。[北部词条「紧」](https://tujia.biz/?id=w1051) · [英语表对应项](https://tujia.biz/?id=w3991) · [原始排名表](../tools/data/syllable-bigrams-ranked.txt) | 拼写卡可显示「紧 / `jìn`」，叶氏拼写为 `jinv`。记音卡使用本字表 `jin` 第四调的「紧」。这里的义项是紧迫。[四调参考字](../tools/data/orthography-defaults.tsv) |

## 查了哪些材料

学堂快照的主词库共有 9,297 个合并词条。其中 6,902 个含明确标为北部的见证，共 17,142 条见证。另读中国语保龙山（60757）、保靖阳朝（60756）、保靖碗米坡（60823）的 7,881 条北部调查词目。查询沿用学堂为各来源和调查点维护的拼写转换。北部见证按自己的原记音、地点与义项关联。泸溪南部语保词目及没有北部见证的词条不纳入此次北部结论。快照版本为 `b882110a9e86b52536f7b8b4bf8179ae5f70f2f0`。实取线上主词库的 SHA-256 为 `7a090fdd4a77a3921aa818ee5a7ec03d4d87b8b839cf37e00ca32dfc3a4a5156`，与快照相同。[学堂主词库](https://tujia.biz/dictionary.json.gz) · [对应源码版本](https://github.com/luggroo/bizic-dictionary/tree/b882110a9e86b52536f7b8b4bf8179ae5f70f2f0/site) · [语保龙山调查词目原文](https://github.com/luggroo/bizic-dictionary/blob/b882110a9e86b52536f7b8b4bf8179ae5f70f2f0/site/yubao-60757-3.json)

## 哪些义项可直接显示

审核表覆盖 1,232 个四调参考键和排名表中额外的 23 个音节。每行分别计数能独立成词的资料、黏着形式及含该音的复音词。能独立成词、有确定转写、汉义一致，且未见与词中成分混淆的音节，可显示来源中的短释义。19 个音节通过。单音节在不同点有不同汉义，或者调查词的意义依赖例句时，通用卡保留空白。有完整词条和上下文支持时，键盘仍可单独推荐该词。主词库有 6 个北部词条未取得可关联的叶氏词形；语保另有 40 个调查词目未能按该点规则完整转换。部分转换中的可辨认片段只提供词内证据。这里的空白表示这个版本的资料还不足以给所有语境套同一汉义。[逐音节审核表](results/syllable-sense-audit.tsv) · [运行时数据](../shared/bundle/Assets/syllable_senses.tsv)

另有 12 个读音已经能按原有规则找到记音字。例如 `hanf` 取 `hangf` 的「汉」，龙山材料的 `munr` 取 `mongr` 的「萌」。这些行只有记音字，读音和汉义各自独立。`tunx`、`tunr` 在这套规则中没有通用参考字。详细审核表的 `equivalentReferenceKey` 和 `referenceHan` 可逐行核对。[学堂记音规则的实现](../tools/han_orthography.py)

## 复现

在项目根目录运行以下命令。`--evidence` 导出每条匹配的源词形、中文意义、调查提示、地点、词条与见证 ID。该详表可用于复查某一地区的同音词；手机安装包中仅含四列简表。

```sh
python3 tools/import_syllable_senses.py ../bizic-dictionary/site --evidence /tmp/north-syllable-witnesses.tsv
python3 qa/test_syllable_senses.py
```
