# 哪些词用汉语本字

共核对 526 个北部土家语词条，44 条采用本字显示。输入汉字时，它们在候选栏只显示本字；切到调号拼音或叶氏拼音时仍可查看汉义。每条记录列出拼写、词义、来源类型和词典编号。[526 项核对表](results/han-source-review.tsv) · [44 项本字及当地对音](../tools/data/plain-han-loans.tsv)

| 依据 | 词条数 | 例子 |
|---|---:|---|
| 北部词典明确标注整词借自汉语 | 34 | `bufdiex` 写「不得」，`jiex` 的“竹节”写「节」。单字「节」的土家语高平读音与北部字音表的低降读音分别记录。 |
| 故事的完整叶氏拼写、词义都与已有明确借源的词条对应 | 2 | `zuif`「最」、`dif`「第」 |
| 项目确认的借词 | 4 | 敢、钢、肝，及《山歌两首》中 `gof` 的“过错”义。表示「他」「讨」时仍显示汉义。 |
| 项目约定的本字 | 4 | 《山歌两首》的「山歌」，以及 `goxgox / goxgov / goxgor` 三种故事读音的「哥哥」。 |

此前的 23 条中，有 18 个词条来自带整词借源标注的北部词典。另有 16 个同类词条此前因当地字音没有逐调吻合被漏掉。这次还纳入「最」「第」及三种「哥哥」。当地字音表可用于比较字音；不同资料的调值与词的借源证据各有单独栏位。[不得](https://tujia.biz/?id=w336) · [节](https://tujia.biz/?id=w1018) · [最](https://tujia.biz/?id=w2754) · [第](https://tujia.biz/?id=w577)

### 哥哥和呼语

龙山故事的重叠式 `gox gox / gox gov / gox gor` 都写「哥哥」。北部词典 w809 收有 `goxgox`，学堂借源审查仍把这个编号列在待查清单。另一个亲属词 `arkov`（w124）被明确排除在近代借词之外。第一段 `ar kox, ar kox ar` 中，后一个表达确实多一个呼语音节；第五段 `gox gov ar` 的末音节也单独写入原始词序。本字约定只覆盖三个重叠式。[原录音、词注及故事](https://tujialanguage.com/texts/longshan-stories-02.html#track-02) · [w809 的审查记录](https://github.com/luggroo/bizic-dictionary/blob/main/docs/loan-coverage-review-2026-10-23.json#L19125) · [w124 的排除记录](https://github.com/luggroo/bizic-dictionary/blob/main/docs/loan-screening-constraints.json#L42)

### 组合词和待考项

「半日猜」只将「半日」记为汉语成分，末音节 `caix` 按土家语记音。两条含 `zaov` 的故事词只核到「早」；`sux liaor` 只核到「输」。这些复合形式均保留整词注义。另列 7 条可复查的异调、亲属称谓和人名，包括 `jiur / jiuf`「就」，词典有明确借源的是 `jiux`。其余 471 条以所据来源暂未获整词借源确证。[早](https://tujia.biz/?id=w2641) · [输](https://tujia.biz/?id=w2156) · [就](https://tujia.biz/?id=w1068)

### “架”的白读和“吃”的词义

词典 w684 的 `gaf` 释作“吃／咬”。键盘用「架」记这个词的音，上方显示「吃」。吉首原始字表把「架」记为 `ka5`，旁注“白，葡萄～”；译写工具的北部对照表给 `gá / ka²⁴`，同样仅列这一白读。这里的葡萄架义与土家语的“吃”分别有各自的来源。[gaf 的词典条目](https://tujia.biz/?id=w684) · [北部字音表](https://github.com/luggroo/bizic-translator/blob/main/data/loan-readings-north.tsv#L4937) · [吉首对照](https://github.com/luggroo/bizic-translator/blob/main/data/loan-sources/jishou.tsv#L1703)

### 复查

从键盘仓库根目录读入学堂网站、北部借词字音表以及演示词库。命令重新扫描每个键盘词条并生成两个 TSV。同一词条先按来源编号核对；跨来源仅采用完整叶氏拼写、完整词义都吻合的唯一北部词条。项目约定、局部可证成分、待考项分别登记。

```sh
python3 tools/prepare_plain_han_loans.py /workspace/bizic-dictionary/site \
  /workspace/bizic-translator/data/loan-readings-north.tsv /workspace/sites/tujia-keyboard/dist
```
