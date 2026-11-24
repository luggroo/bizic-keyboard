# Android 设备与宿主验收

0.3.3（版本码 8）在 AOSP Android 9 云模拟器 `emulator-5562` 上从 0.3.2 覆盖升级。升级后安装时间与应用 UID 均保留。该机为 x86_64，竖屏 480 × 800 px、160 dpi，系统字号 100%。该轮 Android 9 记录对应的 APK 摘要为 `25ff5973…`。后续的候选合并和系统字号在 Android 15 上单独复测。键盘和用于读取结果的宿主分属不同进程。测试点击屏幕键位、注入手写笔画，再读取宿主文本、选区、网页 DOM 或编辑动作。

| 宿主或场景 | 实际结果 |
| --- | --- |
| 原生单行与两个编辑框 | 整句可一次选入。只选长词时，未选拼写保留。候选「中午／半日猜」显示汉义，点选后正文只含「半日猜」；未选拼写随编辑框切换清除。 |
| 汉语借词 | 在汉字记音模式键入 `sanggo`，候选只显示一行「山歌」，选中后正文得到「山歌」。改用带调号拼写，候选上方显示「山歌」，下方显示 `sānggō`，选中后只写入 `sānggō`。 |
| 连续手写与纠字 | 写完「波」再写「俐」，中间无需点候选，两字先后进入正文。只写「波」时，预编辑区显示「波俐」，正文仍只有已写的「波」。手写补全卡的两行是「小孩儿」与「波俐」。可从字形栏改选刚写的一个字，或只写「波」便选择「波俐」。0.3.3 中，选定「小孩、儿童」后可续选「哪」（一），选定「小孩儿」后可续选「哎」（那）。 |
| 词语联想 | 输入「叶」（饭）后，候选上排只写「吃」，下排只写「架」。点选后直接写入「架」。 |
| 下一音节 | 0.3.2 曾在点选音节后展开写法列表。0.3.3 的候选卡直接显示当前输出形式，点一下即写入。 |
| 光标与选区 | 焦点切换会撤下旧的纠字入口，在自动收字前切换焦点，待确认的笔迹不会写进另一输入框。在「首波俐哪波」中选中「波俐」再写「三」，得到「首三哪波」。 |
| 原生多行与键盘窗口 | 0.3.1 中两行文字「架」「半日猜」成功保留；收起键盘后两行仍可读，点回编辑框可重新唤起键盘。第二台模拟器的 0.3.0 横屏手写记录见下文。 |
| 搜索、发送、下一项、上一项 | 宿主收到对应的 Android 动作编号及当前文字「架」。前后移动焦点后，「诶」写入新编辑框。四键的实际无障碍名称与可见名称一致；发送使用本地测试回调。 |
| 原生文本密码和数字密码 | 分别逐键写入 `ga` 和 `12`，真实字段值与预期一致，界面执行掩码显示。键盘采用直接输入，候选栏不展示汉义。 |
| 内嵌 Android System WebView | 在网页 `<input>` 写入「架」，切换 `<textarea>` 后写入两行「半日猜」「诶」。两个控件的 DOM 值分别读回验证。网页密码框接收直接键入的 `ga`。 |
| AOSP 联系人 `com.android.contacts` | 在新建联系人的姓名栏用 `bangricai` 选「半日猜」，读回实际姓名后关闭草稿。 |
| Chromium WebView Shell `org.chromium.webview_shell` | 打开 `about:blank`，选中原地址，在实际浏览器地址栏逐键点 `g`、`a`，读回完整地址栏内容 `ga` 后收起键盘。 |
| AOSP 系统设置 `com.android.settings`（0.3.0 已测） | 搜索框 `android:id/search_src_text` 接收「半日猜」，保留原有设置。 |

原生控件及内嵌网页位于独立的 `biz.tujia.ime.e2e` 测试应用。此镜像的 WebView 提供方是 `com.android.webview` 66.0.3359.158。系统联系人、设置和 Chromium WebView Shell 均为镜像预装应用。厂商键盘管理器、实体手机和未列出的第三方应用仍需在对应环境复核。[宿主兼容矩阵](coverage.md)

## 横屏

在 `emulator-5562` 的软件图形配置 `-gpu off` 下，应用请求横屏时系统出现窗口冻结，随后系统界面 `com.android.systemui` 报告 ANR。系统线程 `WindowAnimator` 阻塞在 `SurfaceComposerClient::Transaction::apply`，设备测试收到 `INSTRUMENTATION_ABORTED: System has crashed`。该轮完成了旋转前的多行输入与收起重开。

第二台 AOSP Android 9 模拟器 `emulator-5564` 为 x86，使用 `swiftshader_indirect` 软件渲染。0.3.0 的发布包从竖屏转为横屏后，编辑框仍在键盘上方；触笔前后手写区边界一致，写入「三」并转回竖屏后保留原有两行文字。0.3.1 的中间构建在这台模拟器也遇到了来宾系统崩溃。最终 APK 的 API 28 横屏行为仍待独立确认。[0.3.1 旋转记录](results/api28-031-rotation-environment.log) [横屏原始记录](results/api28-landscape.log)

[0.3.3 覆盖升级、大字键、词语预测与宿主测试记录](results/api28-device-0.3.3.log) · [0.3.2 覆盖升级、候选可见行、输入和宿主原始记录](results/api28-device-0.3.2.log) · [0.3.1 原始记录](results/api28-device-0.3.1.log)

## 同一个字的不同读音

在 Android 15 模拟器的外部输入框里键入 `ga`，「架」上方显示「吃」，长按能读到「吃、咬」。键入 `jia` 并翻页找到「架」，这张普通汉字候选只显示「架」。先后选入这两张卡，正文字面都是「架」。[输入 ga](results/api35-033-sense-ga-native.png) · [输入 jia](results/api35-033-sense-jia-mandarin.png)

键入 `gogo` 时，「哥哥」在候选里只出现一行，长按也只显示要写入的「哥哥」。同一输入框里先打 `banricai` 再打 `bangricai`，两次都能选「中午／半日猜」，整词上屏。此次 Android 15 测试由工作流从提交 `3ef1307` 构建。[输入 gogo](results/api35-033-sense-gogo-loan.png) · [设备、APK 摘要和逐项结果](results/api35-033-reading-specific.log)

## 0.3.3 联想与输入框

在 480 × 800、160 dpi 的 Android 9 竖屏上，「打字」「手写」各点一次就能切换。打字和手写的输出设置保存在各自模式。顶栏只在输入时显示当前草稿，拼写输出、语音和键盘设置在「更多」里。键盘展开后，系统编辑框仍在上方。默认字号下，字母与候选词形为 20 sp，候选上方的汉义为 14 sp。字母 Q 的实际触区高度为 46 dp，系统字号从 85% 调到 100% 后，Q 字形由 13 px 增至 16 px，触区保持 46 dp。系统的字体大小和显示大小可以分别调整。[系统字号和显示大小](system-type.md) 在 320 dp 宽、系统字号 160% 的屏幕上，候选「女人／麻麻爹」的三个汉字在一格内完整可见。[空闲键盘](results/api28-033-compact-idle.png) · [输入 ga 与四个候选](results/api28-033-compact-typing.png) · [更多选项](results/api28-033-compact-more.png) · [手写区](results/api28-033-compact-handwriting.png) · [窄屏、大字号与复测](compact.md)

在同一个外部输入框里，点「过」直接写入「过」。切到拼写，叶氏模式显示并写入 `gof`，带调号模式显示并写入 `gó`。这几个候选的汉义行留白。键入带第四调的 `jì` 时，带唯一独立义项的 `jìn` 上方显示「紧」，一次点击只写入 `jìn`。长按可看全文，返回时正文保持原样。[点「过」前](results/api28-033-prediction-han-before.png) · [点「过」后](results/api28-033-prediction-han-after.png) · [点 jìn 前](results/api28-033-prediction-jin-before.png) · [点 jìn 后](results/api28-033-prediction-jin-after.png)

- **同字多义**　输入 `go`，同一格的上方显示「¹他 ²讨」，下方显示「过」。长按能看到借词义「过错」，直接点一下会向正文写一个「过」。输入 `gǒ` 仍可选「他／郭」。[无调输入](results/api35-033-han-go.png) · [明确声调](results/api35-033-han-marked-go.png)
- **分词和补全**　键入 `marmardi` 后可直接选择「女人／麻麻爹」。输入 `marmardiexhxif` 后选「麻麻爹」，剩下的 `hxif` 继续保留供选择。
- **手写**　连续写「波」「俐」并选定「小孩、儿童」后，点音节候选「博」，外部输入框读回「波俐博」。切换为手写拼写输出时，点候选 `gó` 会写入带调拼写。
- **当前输入框的用词习惯**　用唯一义的「中午／半日猜」接在「饭／叶」后面，可以训练当前输入框里的下一词联想。切到另一个输入框，首个联想恢复为「吃／架」。输入「叶过。叶过。叶」时，用户选了两次多义字「过」，键盘会保留原来的「吃／架」，不会把「过」记成两次“他”。要求关闭个性化学习的输入框采用固定词序。密码框接受直接输入的字母。[Android 15 同字候选与学习记录](results/api35-033-han-senses.log)

## 复现

连接已解锁的 Android 设备。若连接多台设备，以 `ANDROID_SERIAL` 指定目标。下列命令在项目根目录执行。

```sh
./tools/build-apk.sh ime.apk
./tools/build-apk.sh --device-test fixture.apk
./device-test/run.sh ime.apk fixture.apk
TUJIA_E2E_SUITE=system ./device-test/run.sh ime.apk fixture.apk
```

默认套件涵盖核心输入、宿主、横屏和下一音节。以 `TUJIA_E2E_SUITE=core`、`host`、`window`、`web` 或 `sounds` 可分别复测。选配的 `system` 套件要求已安装上述 AOSP 联系人与 Chromium WebView Shell。脚本退出时恢复原输入法及显示硬件键盘的设置。Android 15 的标准模拟器可通过仓库工作流运行同一套件。[设备工作流](../.github/workflows/device-qa.yml)
