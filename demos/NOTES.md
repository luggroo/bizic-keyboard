两个安装包都支持离线拼音和国际音标输入。本次覆盖更新保留 32 位 ARM、64 位 ARM 和 x86_64，已移除 32 位 x86。已安装 0.3.6 的用户可以直接覆盖安装。需要在键盘上写汉字，请选手写版。

- [土家语输入法_有手写_v0.3.6.apk](https://raw.githubusercontent.com/luggroo/bizic-keyboard/37fa27046d1c27ce3c74eb8819518fe68d8663f0/downloads/土家语输入法_有手写_v0.3.6.apk)
- [土家语输入法_无手写_v0.3.6.apk](https://raw.githubusercontent.com/luggroo/bizic-keyboard/37fa27046d1c27ce3c74eb8819518fe68d8663f0/downloads/土家语输入法_无手写_v0.3.6.apk)

### 候选和输入

每格上面是汉义，下面是完整词形。字号变大时，每页按实际字宽排版，单个词超过一行时会换行。输入与词库完全匹配且有释义的字词显示为绿色，长按可以查看完整释义。

同一 IPA 词条的音节连写。例如点选「乳头」会输入 `mã²¹pu³⁵li⁵⁵`，词与词之间可以按空格。键盘的「打字」标签改为「拼音」。

手写切换到拼音或音标输出后，单字优先列四调，同一读音合并汉义。例如写「史」后依次可选 `siv、six、sir、sif`。手写汉字候选栏只显示有释义的词，左侧可以改选识别字形。

### 安装与签名

两个 0.3.6 安装包共用包名和签名，可以互相覆盖安装。已安装 0.3.5 公开版的设备无法直接覆盖升级，因为签名不同。卸载旧版会清除它的设置和个人词库。

[下载 SHA-256 校验值](https://github.com/luggroo/bizic-keyboard/releases/download/v0.3.6/SHA256SUMS)

### 桌面图标与手写实测

Android 8 及以上使用铺满图标的自适应图案，浅色主题使用 A3，深色主题使用 B2。Android 7 保留传统图标。两种配色均在 Android 15 启动器核对，150% 字号下手写「我」的首个候选显示为绿色。

[浅色桌面图标](https://raw.githubusercontent.com/luggroo/bizic-keyboard/117c411e0351ce6a7429a1f6de845e113188c0c5/qa/results/v0.3.6/launcher-adaptive-light.png) · [深色桌面图标](https://raw.githubusercontent.com/luggroo/bizic-keyboard/117c411e0351ce6a7429a1f6de845e113188c0c5/qa/results/v0.3.6/launcher-adaptive-dark.png) · [手写候选截图](https://raw.githubusercontent.com/luggroo/bizic-keyboard/117c411e0351ce6a7429a1f6de845e113188c0c5/qa/results/v0.3.6/handwriting-match-150.png)

### 0.3.6 输入演示

[离线互动 Demo](https://github.com/luggroo/bizic-keyboard/releases/download/v0.3.6/shange-demo.zip)（解压后打开 `index.html`）

- 安卓录屏：[记事本输入四句山歌](https://github.com/luggroo/bizic-keyboard/releases/download/v0.3.6/shange-demo.mp4) · [连续手写「波俐」](https://github.com/luggroo/bizic-keyboard/releases/download/v0.3.6/continuous-handwriting.mp4) · [点选下一个词](https://github.com/luggroo/bizic-keyboard/releases/download/v0.3.6/one-tap.mp4) · [吉首文白异读](https://github.com/luggroo/bizic-keyboard/releases/download/v0.3.6/jishou-readings-android.mp4) · [声调与国际音标](https://github.com/luggroo/bizic-keyboard/releases/download/v0.3.6/tone-demo.mp4)
- 网页录屏：[拼写与连续点选](https://github.com/luggroo/bizic-keyboard/releases/download/v0.3.6/web-prediction.mp4) · [文白异读与借词](https://github.com/luggroo/bizic-keyboard/releases/download/v0.3.6/web-readings.mp4)

