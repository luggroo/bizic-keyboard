# 北部土家语输入法 · 安卓键盘与 iOS 共用核心

这是一个可在其他安卓应用里使用的系统键盘。键盘收录 526 个北部土家语词和词组，另有 35,925 项普通汉语候选。查词、分词、手写识别和后续词推荐均在本机运行。语音入口已预留。

## 安装与试打

1. 在 Android 7.0 或以上的手机上下载并安装试用 APK，打开「土家语输入法」。[下载安卓安装包](https://github.com/luggroo/bizic-keyboard/releases/latest)
2. 点「在系统设置中启用」，开启土家语输入法。回到应用，点「选择土家语键盘」。
3. 点应用里的试打框，或在记事本里新建一条笔记。键盘上方可以直接切换「打字」「手写」。点「更多」，三个键上方的灰字展示“土家族”的三种写法。点「用叶氏拼音」「用带调号拼音」或「用汉字谐音」，输入 `bizika`，再点汉义为“土家族”的候选，正文会写入 `bifzixkar`、`bízīkǎ` 或「币子咔」。输入到一半也可以切换写法。HTML 演示中的「试写毕兹卡」会直接打开这三个键。

「打字」和「手写」同排，点一下就切换。当前拼写显示在这一行，记事本正文留在键盘上方。字母键、候选和汉义跟随手机的系统字号。想放大文字，可以在手机设置里搜索“字体大小”。想让按键和其他界面元素一起变大，可以搜索“显示大小”或“屏幕缩放”。[各品牌的设置入口](qa/system-type.md) · [打字画面](qa/results/api28-033-compact-typing.png) · [手写画面](qa/results/api28-033-compact-handwriting.png)

## 在记事本里写山歌

演示用的是毕兹学堂收录的龙山《山歌两首》第一首。下面按原文的四行排版。第三列是键盘按词输出的试用记音字，汉义显示在候选上方。[毕兹学堂原文，第 190–191 页](https://tujia.biz/?id=xu-tujia-annotated-2-13#corpus?corpusCollection=xu-tujia-annotated-texts)

| 原文汉译 | 带调号拼写 | 记音汉字 |
| --- | --- | --- |
| 我们都是土家族， | ngánī húnī bízīkǎ ō， | 讶你互你币子咔喔， |
| 唱山歌都用土家语， | sānggō cáng diē bízīsǎ ō， | 山歌唱爹币子刹喔， |
| 唱一句土家山歌， | bízī sānggō nā zē cáng， | 币子山歌哪遮唱， |
| 唱得不好别怪我。 | cáng xī rè tái gó tà ǎ lē。 | 唱兮惹太过塔吖里。 |

[互动 Demo（解压后打开 index.html）](https://github.com/luggroo/bizic-keyboard/releases/latest/download/shange-demo.zip) · [逐词试打清单](tools/story-demo/shange-spelling.tsv) · [输入演示录像](https://github.com/luggroo/bizic-keyboard/releases/latest/download/shange-demo.mp4)

「记音」输出词表的推荐汉字。像《山歌两首》里的 `sānggō`，键盘按学堂词条直接给「山歌」，汉字上方留白。拼写输出仍显示“山歌”以便选词。候选区每页四格。长按候选可以查看完整释义。土家语词列在前面，普通汉字和汉语词列在后面。可以把几个词的拼写连着打。比如输入 `marmardiexhxif`，先点“女人”下的「麻麻爹」，再为余下的 `hxif` 选“走”下的「诶」。整句候选可用时，也可以一次上屏。

无调输入 `go` 时，候选栏只出现一个「过」。上方写「¹他 ²讨」，空间紧时显示「¹他²讨」。点一下就写入「过」。长按可查看这两个义项，以及山歌里用本字「过」表示“过错”的借词用法。输入 `gǒ` 或 `gor` 可以选「郭」表示“他”。[安卓上输入 go](qa/results/api35-033-han-go.png) · [安卓上输入 gǒ](qa/results/api35-033-han-marked-go.png)

「哥哥」和「山歌」这样的本字词在汉字候选中只占一行。词表把 `gōgō` 释为“哥哥”。故事里的汉译保留原句语气。呼语 `ǎkō’ǎ` 多一个音节，可以选出“哥哥啊”。[学堂“哥哥”词条](https://tujia.biz/?id=w809#dictionary) · [龙山故事原文](https://tujia.biz/?id=tlp-longshan-stories-02-01#corpus)

`an`、`ang` 在音节末尾可以互用。输入 `banricai` 和 `bangricai` 都能选“中午”下的「半日猜」。打 `ga` 能看到“吃”下的「架」。打 `jia` 也能选「架」，字上方留白。李启群《吉首方言研究》第 39 页给「架」的吉首白读 `ka5` 举「十字架儿」，给文读 `tɕia5` 举「吵架」。同页的「庄稼」两读都有，输入 `zhuangga` 或 `zhuangjia` 都可选整个词。[文白异读对照表](https://github.com/luggroo/bizic-translator/blob/main/data/han-registers.xlsx) · [词库借词来源](qa/han-source-review.md)

也可以直接打调号。长按 A、E、I、O、U 会出现四个带调元音，点一个即可输入。例如输入 `bízīkǎ` 可以查到“土家族”。输出仍按设置里选择的拼写方案。

正字表的 308 个音节各有四个声调，共 1,232 格。x、v 两调共用默认记音字；同音词的已确认词义写法按词条保留。an/ang、ian/iang、uan/uang 共用默认字，bo/buo、ko/kuo 可互换输入。153 格用两个汉字记一个音节，例如 `kǐ` 写作「克移」。候选和正文用「˻ ˼」包住表示一个音节的两个字，例如「˻克移˼」。括号是普通文本，在纯文本输入框里也会保留。支持格式的输入框会将括号内的两个字缩窄并加下划线。像「波俐」这样分属两个音节的词按普通字宽显示。「中午」写作「半日猜」。「半日」保留汉语借词本字，「猜」记土家语音节 `caix`。`zi`、`ci`、`si`、`ri` 四组的 16 个声调格都用单字。`ri` 四调的默认字都是「日」，查词时可以通过拼写和汉义区分。

「敢」「钢」「肝」分别按词义选本字。「诶」「给」「尅」「黑」等常用写法在相应词条保留。hxe 四调依次写作「呃、轭、额、呃」。名物化 xi 写作「兮」，xi ge 写作「兮膈」。源表 `tools/data/orthography-defaults.tsv` 的 `review` 栏记录照片中需要复核的字形。

安卓手写模型随安装包提供，无需联网。模型源自 [Ismantic/Handwritten](https://github.com/Ismantic/Handwritten)，使用 ONNX Runtime 在设备上运行。

在「手写」里写完一个字，首选字会直接进入正文。记成「波粒」也能找到「波俐」（小孩）。认错字时，点「改字」旁的其他字形即可更正。

「已写成词」查光标前最多四个汉字，包含刚写下的字，遇到标点、空格或换行就停。「˻阿安˼」算两个汉字，替换时连同两侧的合音标记一起处理。写「树稀步李」，候选栏会同时列出「树希布里」（板栗）、「希布里」（星）和「布里」（水果）等词。当地同音字优先按吉首话匹配，缺少吉首读音时参考重庆话。可以继续写，也可以点某个词看汉义，选叶氏拼音、带调号拼音或推荐汉字，只替换这个词。点「返回」可保留手写的字。[板栗](https://tujia.biz/?id=w2169) · [星](https://tujia.biz/?id=w2432) · [水果](https://tujia.biz/?id=w341)

点「接着写」可选下一个字。例如写「波」后点「俐」，只追加「俐」。词库里能接上的字排在前面，后面是按上下文推荐的词和音节。手写时，点「更多」选定一种写法，接下来写的词就用这种形式上屏。

### 试试自由输入

候选栏每页四项。点一个词或音节，它就进入正文，键盘接着给下一组候选。打到一半也可以选。例如在「记音」下打 `marmardi` 就能点“女人”下的「麻麻爹」，省去词尾的 `ex`。已打出的整词排在补全词前面。手写时也可以随时点整词补全。

在「拼写」下输入 `bizika`，选“土家族”下的 `bízīkǎ`。再点 `mǒng`，正文变为 `bízīkǎ mǒng`。如果选 `mǔn`，正文就用 `mǔn`。词义明确时，上方显示简短汉义。例如输入完整的 `kamong` 可以选“树”下的 `kǎmǒng`。单独预测到 `mǒng` 时汉义留空，因为它还出现在“呻吟”和“线”等词里。龙山有一个读作 `mǒng` 的地支词，选到这个词条时才显示“地支午”。[“树”的词条](https://tujia.biz/?id=w1102) · [龙山调查“午”](https://tujia.biz/?id=yubao-60757-v-2498) · [音节汉义核对](qa/syllable-senses.md)

[看安卓记事本里点候选的短片](https://github.com/luggroo/bizic-keyboard/releases/latest/download/one-tap.mp4) · [查看点按前](qa/results/web-033-free-suggestions.png) · [查看点按后](qa/results/web-033-direct-sound.png)

后续词按跨篇出现的搭配、词类和使用频率推荐。键盘也会参考你在当前输入框里刚选过的搭配。换输入框就清空这段记录。可用的词先显示，余下位置再给常接的音节。训练时排除了演示用的两篇作品。19 组整篇留出测试共 10,404 个词位，首屏下一词有 85 次命中目标词义。1,905 个词位能写出目标汉字，其中有 1,427 个可以点选到明确的词义。在这 1,427 个位置，利用下一词和前缀补全，可以把全拼的 5,481 次按键降到 3,252 次。[评测方法和分组结果](qa/prediction.md) · [1,000 组相邻音节](tools/data/syllable-bigrams-ranked.txt)

## 安卓和 iOS 如何共用

两端读取同一份词表和记音变体，通过同一个 C++17 引擎做分词、候选排序和同音选义。安卓手写使用本机 Ismantic/Handwritten 模型；模型字表以外的字由原有笔画模板补充。iOS 核心继续使用笔画模板。选中部分候选后截取剩余拼写、在相邻拼写间补空格，也都由核心处理。改动 `shared/bundle/Assets` 后重新构建两端，即可一起更新词表。

| 目录 | 用途 |
| --- | --- |
| `shared/src` | 离线词典、分词、笔画识别和选词操作 |
| `shared/include/tujia_core.h` | 使用 UTF-8 的 C 接口，选词长度按 UTF-16 计算 |
| `app/src/main` | Android 系统键盘、触摸界面、编辑框连接与 JNI 适配 |
| `shared/bundle` | 两端词库和字形资源、Swift 接口及 iOS 编辑框适配 |
| `apple/Tests` | Swift 端词条、分词、输出边界和手写回归测试 |

在 Xcode 中将本目录作为本地 Swift Package 加入 iOS 键盘扩展，选择产品 `TujiaInput`。在 `UIInputViewController` 里，先取候选并展示汉义，用户点选后再调用 `commit`。它通过 `textDocumentProxy` 写入词，并返回尚待选择的输入。包支持 iOS 13 起。工程包含可安装的安卓键盘，iOS 提供给键盘扩展使用的程序库。

```swift
import TujiaInput
import UIKit

final class KeyboardController: UIInputViewController {
    private var dictionary: TujiaDictionary?
    private var draft = ""

    override func viewDidLoad() {
        super.viewDidLoad()
        dictionary = try? TujiaDictionary()
    }

    func updateInput(_ value: String) -> [HanCandidateGroup] {
        draft = value
        return dictionary?.hanCandidates(for: draft) ?? []
    }

    // 显示 group.caption() 和 group.primary.text。窄格可用 group.caption(compact: true)。
    func select(_ candidate: WordCandidate) -> [HanCandidateGroup] {
        guard let rest = dictionary?.commit(candidate, in: draft, to: textDocumentProxy)
        else { return [] }
        return updateInput(rest)
    }
}
```

点候选时传入 `group.primary`。长按时也可选择 `group.alternativeEdits` 里的较短拼写。例如输入 `rif`，点击「日」会用掉整个拼写，长按后选择 `ri` 会留下 `f`。多义卡只有在能确定词条时才提供词条 ID，多个读音共用同一个字时，音节上下文留空。

`TujiaHandwriting.glyphs(for:)` 接收依书写顺序排列的笔画。把记音字交给 `dictionary.candidates(for:form:)`，使用 `.handwritingToSpelling` 取土家语拼写，或使用 `.recommendedHan` 取推荐汉字。`handwritingSuggestions(for:form:orthography:)` 根据已经写下的字补全词语，`spellingCompletions(_:after:form:orthography:)` 根据已打出的拼写补全词尾。

手写字上屏后，把光标前的文字交给 `handwrittenWords(beforeCaret:form:orthography:)`。每个结果带有原文的 UTF-16 范围，允许替换同一位置上两字、三字或四字的词。单字也会返回。`nextHandwrittenGlyphs(beforeCaret:)` 提供只需追加的下一个字。

`nextWords(after:form:orthography:)` 接收当前输入框最近选过的词条 ID，以空格分隔。一个或两个前词可用于普通预测。传入最多 32 项可让最近重复选择的搭配提前。已知的句首传入 `<s>`。`nextSyllables(after:form:orthography:)` 接收最近一个已确认音节或句首标记，按所选输出形式返回可直接提交的候选。

## 构建安卓安装包

使用 Android Studio 打开本目录，或在装有 JDK 17、Android SDK Platform 35、Build Tools 35.0.0、NDK 28.2.13676358 和 CMake 3.22.1 的环境中运行。Gradle Wrapper 和预生成的离线词表已随工程附带。

```sh
./gradlew :app:assembleDebug
```

也可以用构建脚本生成测试包。先将 `ANDROID_HOME` 指向 SDK，再运行以下命令。

```sh
./tools/build-apk.sh build/tujia-keyboard.apk
```

提交到 main 或打开拉取请求后，GitHub Actions 会运行核心测试、构建 APK 并校验签名。每次成功构建的页面都能下载 `bizic-keyboard-debug`。手机上连续升级试用版，请使用 Releases 中由同一证书签名的 APK。[构建记录](https://github.com/luggroo/bizic-keyboard/actions/workflows/android.yml)

本地构建脚本使用 Gradle 的 Android 调试签名。

## 测试

设备实测、兼容性矩阵、词库命中率和输入延迟的口径见[安卓输入法 QA 报告](qa/README.md)。

在有 C++17 编译器和 JDK 的 Linux 或 macOS 上运行核心与 JNI 测试。在有 Swift 5.9 或更新版本的环境中运行 Swift 包测试。

```sh
./tools/test-core.sh
swift test
```

设备端测试另有一个包含两个输入框的应用。在已经解锁的测试机或模拟器上，运行以下命令。脚本会安装输入法和测试应用，使用 Android 的系统测试接口点按屏幕，核对输入框中的文字，最后恢复原来的输入法设置。连接多台设备时可用 `ANDROID_SERIAL` 指定测试设备。

```sh
./device-test/run.sh
```

## 词库与字形

补充词库中，词典已明确标注整词汉借、且借源本字与词义对齐的词使用本字；「国家」的已记录异读也写作「国家」。

两端读取 `shared/bundle/Assets/words.tsv` 和 `northern-words.tsv`。每行带有语言类别、独立的数值词频、简短汉义、完整释义、推荐汉字、无调拼写和带调号拼写。在同类候选中优先提供能覆盖更多输入的词或整句；同长度候选按该语言词频排序。

土家语权重是毕兹学堂北部语料中可对齐到相应词形和义项的出现次数。没有可判定义项的语料记为 0。普通汉语权重来自 wordfreq 的中文词频估计，保存为 Zipf 值的 100 倍。来源版本、匹配方法、授权文件见 `shared/bundle/Assets/licenses`；手写字形的授权见 `shared/bundle/Assets/handwriting`。

录制工具在 `tools/story-demo`。山歌的无调按键、叶氏拼音、带调号拼写、记音字和简短汉义可在同一份清单里逐词核对。[查看《山歌两首》输入清单](tools/story-demo/shange-spelling.tsv)

更新语料时，先把正字及词条例外应用到基础词表，再从毕兹学堂原始数据重建故事词和词频。使用 Python 3 和 Node 运行：

```sh
python3 tools/apply_orthography.py /path/to/demo/dist
python3 tools/story-demo/prepare_story.py /path/to/bizic-dictionary /path/to/bizic-translator /path/to/demo/dist
python3 tools/prepare_han_readings.py /path/to/bizic-translator /path/to/pypinyin-0.55.0-py2.py3-none-any.whl
python3 tools/import_words.py /path/to/demo/dist --dictionary-site /path/to/bizic-dictionary/site --web /path/to/demo/dist/ranking.js
python3 tools/import_ngrams.py /path/to/bizic-dictionary/site
python3 tools/import_syllable_senses.py /path/to/bizic-dictionary/site
```

如有新的土家语词频，可以将 `id` 和 `frequency` 两列保存为制表符分隔的 UTF-8 文件，重新导入时加上 `--tujia-frequencies 文件路径`。文件中列出的词使用新计数，其余词继续使用语料估计。词 ID 对应 `words.tsv` 的首列，数值为非负整数。

安卓系统键盘的生命周期与上屏交互参考了[同文输入法 Trime](https://github.com/osfans/trime)。
