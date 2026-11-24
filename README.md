# 北部土家语输入法 · 安卓键盘与 iOS 共用核心

这是一个可在其他安卓应用里使用的系统键盘。键盘收录 510 个北部土家语词和词组，其中包含《陈二郎戏弄哥哥》的语料词组，另有 35,916 项普通汉语候选。查词、分词和手写识别均在本机运行。语音入口已预留，当前版本还不能听写。

## 安装与试打

1. 在 Android 6.0 或以上的手机上安装 APK，打开「土家语输入法」。
2. 点「在系统设置中启用」，开启土家语输入法。回到应用，点「选择土家语键盘」。
3. 点试打框，或打开短信等应用的输入框。在「记音」模式输入 `jir`，找到上方标有“脚”的「及」。点选后，输入框里会出现「及」。

在「拼写」模式输入无调的 `aizu`，可选上方写着“从前”的词。在应用设置里点「拼写输出」，选择「带调号拼音」会写入 `āizū`，选择「叶氏拼音」会写入 `aixzux`。设置也适用于手写转拼写。

「记音」输出词表的推荐汉字。候选区每页四格。先看一条常用汉义，长按候选可以查看完整释义。土家语词列在前面，普通汉字和汉语词列在后面。可以把几个词的拼写连着打。比如输入 `marmardiexhxif`，先点“女人”下的「麻麻爹」，再为余下的 `hxif` 选“走”下的「诶」。整句候选可用时，也可以一次上屏。

也可以直接打调号。长按 A、E、I、O、U 会出现四个带调元音，点一个即可输入。例如依次选 `ā`、打 `i`、`z`、选 `ū`，用 `āizū` 查“从前”。输出仍按设置里选择的拼写方案。

正字表的 308 个音节各有四个声调，共 1,232 格。190 格用两个汉字记一个音节，例如 `kǐ` 写作「克移」。候选卡用同一条线标出连读，两字略窄。写入支持富文本的应用时一并带入样式，纯文本应用保留两个汉字。像「波俐」这样分属两个音节的词按普通字宽显示。「中午」写作「半日猜」。「半日」保留汉语借词本字，「猜」记土家语音节 `caix`。`zi`、`ci`、`si`、`ri` 四组的 16 个声调格都用单字。`ri` 四调的默认字都是「日」，查词时可以通过拼写和汉义区分。

「敢」「钢」「肝」分别按词义选本字。「诶」「给」「尅」「黑」等常用写法在相应词条保留。无调虚词与词尾另列。源表 `tools/data/orthography-defaults.tsv` 的 `review` 栏记录照片中需要复核的字形。

「手写」先认字形，再按汉义选词。写「尬」或「嘎」后点对应字形，再从候选中选择想表达的词。用「输出拼写／输出记音」切换上屏形式。离线笔画模板覆盖 3,559 个字，包含全部土家语试用词的记音字和变体。

## 安卓和 iOS 如何共用

两端读取同一份词表、记音变体和手写字形，通过同一个 C++17 引擎做分词、候选排序、同音选义和笔画匹配。选中部分候选后截取剩余拼写、在相邻拼写间补空格，也都由核心处理。改动 `shared/bundle/Assets` 后重新构建两端，即可一起更新词表。

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

    func updateInput(_ value: String) -> [WordCandidate] {
        draft = value
        return dictionary?.candidates(for: draft, form: .recommendedHan) ?? []
    }

    // 卡片显示 candidate.shortMeaning 和 candidate.text，完整汉义见 candidate.meaning。
    func select(_ candidate: WordCandidate) -> [WordCandidate] {
        guard let rest = dictionary?.commit(candidate, in: draft, to: textDocumentProxy)
        else { return [] }
        return updateInput(rest)
    }
}
```

`TujiaHandwriting.glyphs(for:)` 接收依书写顺序排列的笔画。点选字形后，把记音字交给 `dictionary.candidates(for:form:)`，使用 `.handwritingToSpelling` 取土家语拼写，或使用 `.recommendedHan` 取推荐汉字。使用 `candidates(for:form:orthography:)` 指定输出的拼写方案。

## 构建安卓安装包

使用 Android Studio 打开本目录，或在装有 JDK 17、Android SDK Platform 35、Build Tools 35.0.0、NDK 28.2.13676358 和 CMake 3.22.1 的环境中运行。Gradle Wrapper 和预生成的离线词表已随工程附带。

```sh
./gradlew :app:assembleDebug
```

也可以直接用 SDK 工具生成、对齐并签名测试包。此脚本用到 Bash、Python 3 和 zip。先将 `ANDROID_HOME` 指向 SDK，再运行以下命令。

```sh
./tools/build-apk.sh build/tujia-keyboard.apk
```

脚本使用本机的 Android 调试签名。可用 `TUJIA_DEBUG_KEYSTORE` 指定测试密钥位置。

## 测试

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

两端读取 `shared/bundle/Assets/words.tsv`。每行带有语言类别、独立的数值词频、简短汉义、完整释义、推荐汉字、无调拼写和带调号拼写。在同类候选中优先提供能覆盖更多输入的词或整句；同长度候选按该语言词频排序。

土家语权重是毕兹学堂北部语料中可对齐到相应词形和义项的出现次数。没有可判定义项的语料记为 0。普通汉语权重来自 wordfreq 的中文词频估计，保存为 Zipf 值的 100 倍。来源版本、匹配方法、授权文件见 `shared/bundle/Assets/licenses`；手写字形的授权见 `shared/bundle/Assets/handwriting`。

录制脚本保存在 `tools/story-demo`。两句录屏在独立的安卓记事本里演示拼音、记音汉字和手写。发布视频将按键和选词整理为每两秒三个音节，手写「尬」的七笔用一秒完成。第一句写“我吃饭”，第二句写“中午吃饭”，通过选词分别写入 `ngá yě gá。` 和「半日猜叶架。」。录制结束后，系统测试保存并重开笔记，核对两行正文。

互动 Demo 另附《陈二郎戏弄哥哥》全篇六段的输入清单。原文保留了 274 个标注词和词组，共 476 个音节。词形、逐词释义和段落顺序来自[毕兹学堂的龙山语料](https://tujia.biz/?id=tlp-longshan-stories-02-01)。汉字记音沿用试用字表，故事新增的配字仍是试写。

更新语料时，先把正字及词条例外应用到基础词表，再从毕兹学堂原始数据重建故事词和词频。使用 Python 3 和 Node 运行：

```sh
python3 tools/apply_orthography.py /path/to/demo/dist
python3 tools/story-demo/prepare_story.py /path/to/bizic-dictionary /path/to/bizic-translator /path/to/demo/dist
python3 tools/import_words.py /path/to/demo/dist --dictionary-site /path/to/bizic-dictionary/site --web /path/to/demo/dist/ranking.js
```

如有新的土家语词频，可以将 `id` 和 `frequency` 两列保存为制表符分隔的 UTF-8 文件，重新导入时加上 `--tujia-frequencies 文件路径`。文件中列出的词使用新计数，其余词继续使用语料估计。词 ID 对应 `words.tsv` 的首列，数值为非负整数。

安卓系统键盘的生命周期与上屏交互参考了[同文输入法 Trime](https://github.com/osfans/trime)。
