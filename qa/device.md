# 土家语输入法 Android 设备验收

0.3.0（版本码 5）在 Android 9 的 AOSP x86_64 云模拟器 `emulator-5562` 上通过了下列已标记的项目。竖屏为 480 × 800 px、160 dpi，系统字号 100%。横屏为 800 × 480 px。测试向独立宿主的输入法窗口注入触摸事件，读取宿主实际文本、控件焦点和系统输入动作。

| 宿主与场景 | 核验内容 | 结果 |
| --- | --- | --- |
| 原生单行输入框 | 整句优先、先选长词再选余串；有汉义的词显示释义，普通汉字仅有文字。上屏保留所选输出形式。 | 已通过 |
| 手写与光标 | 逐笔写「波」「俐」，两字自动进入编辑框。写「波」可选「坡」，还可补成「波俐」；按具体词义选择不同后续词。切换编辑框时放弃尚未写完的字，移动光标和选区后仅编辑当前位置。 | 已通过 |
| 原生多行与窗口 | 输入两行「架」「半日猜」，隐藏并重新唤起键盘；旋转横屏写「三」，检查手写区坐标固定，编辑框仍在其上方。 | 设备复测中 |
| 搜索与发送 | 宿主收到对应的 Android 动作编号和完整文本。发送使用测试回调。可见键名与无障碍名称一致。 | 设备复测中 |
| 下一项与上一项 | 通过键盘在两个编辑框之间移动，随后选词落在新焦点上。 | 设备复测中 |
| 原生密码和数字密码 | 逐键直输，输入框执行掩码显示，键盘不显示词义候选。 | 设备复测中 |
| Android System WebView | 页面 `<input>`、`<textarea>`、密码控件分别验证真实 DOM 值，包括换行、隐藏后再弹出，以及切换网页控件时丢弃未选拼写。 | 设备复测中 |
| AOSP 联系人 `com.android.contacts` | 在新建联系人的姓名栏用 `bangricai` 选择「半日猜」，读回姓名后放弃草稿。 | 设备复测中 |
| Chromium WebView Shell `org.chromium.webview_shell` | 浏览器原生地址栏接收直接键入的字母。保留 `about:blank`，未发起网页访问。 | 设备复测中 |

原生控件与内嵌网页位于独立的 `biz.tujia.ime.e2e` 测试应用中。WebView 提供方为 `com.android.webview` 66.0.3359.158。系统联系人和 Chromium WebView Shell 是镜像预装应用。微信、其他浏览器及厂商定制输入框的兼容性仍需在对应应用中验证。

API 28 首轮动态横屏切换时，系统日志报告 WindowManager 窗口冻结及系统界面 `com.android.systemui` ANR，测试进程收到 `INSTRUMENTATION_ABORTED: System has crashed`。键盘旋转的跨版本结论须结合独立设备结果。

## 复现

连接一台解锁的 Android 设备。若连接多台设备，设置 `ANDROID_SERIAL`。在项目根目录运行下列命令，其中 `ime.apk` 与 `fixture.apk` 分别为待测键盘及测试宿主安装包。

```sh
./tools/build-apk.sh ime.apk
./tools/build-apk.sh --device-test fixture.apk
./device-test/run.sh ime.apk fixture.apk
TUJIA_E2E_SUITE=system ./device-test/run.sh ime.apk fixture.apk
```

默认套件运行 `core`、`host` 和 `window`。可用 `TUJIA_E2E_SUITE=core` 单独验收输入逻辑，或以 `TUJIA_E2E_SUITE=host` 单独验收原生控件和 WebView。`TUJIA_E2E_SUITE=window` 单测多行、键盘显示和旋转。`system` 要求设备上已安装上述两个 AOSP 应用。运行脚本会恢复进入测试前选中的输入法及硬件键盘显示设置。
