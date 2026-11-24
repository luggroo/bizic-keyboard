# Android 设备与宿主验收

0.3.1（版本码 6）在 AOSP Android 9 云模拟器 `emulator-5562` 上从 0.3.0 覆盖升级。该机为 x86_64，竖屏 480 × 800 px、160 dpi，系统字号 100%。发布 APK 的 SHA-256 为 `059e2930521f1855272d8432c0002447a90543dada86e695fcfa08e31912077b`。键盘和用于读取结果的宿主分属不同进程。测试点击屏幕键位、注入手写笔画，再读取宿主文本、选区、网页 DOM 或编辑动作。

| 宿主或场景 | 实际结果 |
| --- | --- |
| 原生单行与两个编辑框 | 整句可一次选入。只选长词时，未选拼写保留。候选「中午／半日猜」显示汉义，点选后正文只含「半日猜」；未选拼写随编辑框切换清除。 |
| 汉语借词 | 在汉字记音模式键入 `sanggo`，候选只显示一行「山歌」，选中后正文得到「山歌」。改用带调号拼写，候选上方显示「山歌」，下方显示 `sānggō`，选中后只写入 `sānggō`。 |
| 连续手写与纠字 | 写完「波」再写「俐」，中间无需点候选，两字先后进入正文。可从字形栏改选刚写的一个字，或只写「波」便选择「波俐」。同形的两个词义分别可以续选「太」（没有）和「再」（大人）。 |
| 下一音节 | 点句首预测 `gof` 可先浏览「他」「过错」，返回时正文仍为空。选择「他」才写入「过」。在「叶」（饭）后浏览 `gaf` 同样保留原文，选择「吃」后得到「叶架」。从设置回到同一编辑框，由宿主清空内容后，键盘重新显示句首候选。 |
| 光标与选区 | 焦点切换会撤下旧的纠字入口，在自动收字前切换焦点，待确认的笔迹不会写进另一输入框。在「首波俐太波」中选中「波俐」再写「三」，得到「首三太波」。 |
| 原生多行与键盘窗口 | 两行文字「架」「半日猜」成功保留；收起键盘后两行仍可读，点回编辑框可重新唤起键盘。第二台模拟器的 0.3.0 横屏手写记录见下文。 |
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

[0.3.1 覆盖升级、输入和宿主原始记录](results/api28-device-0.3.1.log) · [下一音节原始记录](results/api28-next-sound.log)

## 复现

连接已解锁的 Android 设备。若连接多台设备，以 `ANDROID_SERIAL` 指定目标。下列命令在项目根目录执行。

```sh
./tools/build-apk.sh ime.apk
./tools/build-apk.sh --device-test fixture.apk
./device-test/run.sh ime.apk fixture.apk
TUJIA_E2E_SUITE=system ./device-test/run.sh ime.apk fixture.apk
```

默认套件涵盖核心输入、宿主、横屏和下一音节。以 `TUJIA_E2E_SUITE=core`、`host`、`window`、`web` 或 `sounds` 可分别复测。选配的 `system` 套件要求已安装上述 AOSP 联系人与 Chromium WebView Shell。脚本退出时恢复原输入法及显示硬件键盘的设置。Android 15 的标准模拟器可通过仓库工作流运行同一套件。[设备工作流](../.github/workflows/device-qa.yml)
