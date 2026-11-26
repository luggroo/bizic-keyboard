# Android 输入法 QA · 0.3 系列

0.3.1 修复了在设备负载较高时连续点同一个键偶尔漏字的问题。原始记录分别标注测量版本和安装包摘要。验收覆盖拼写、记音、连续手写以及宿主收到的文字。安卓端用跨进程测试操作已启用的系统输入法，再从宿主读取正文、选区和编辑动作。词库评测直接调用安卓与 iOS 共用的候选引擎。

| 想了解的问题 | 测试依据 |
| --- | --- |
| 搜索、发送、换行、切换输入框和改字是否作用在正确位置 | [宿主与窗口设备实测](device.md) |
| 手机的字体和显示大小在哪里调，两种设置各影响什么 | [系统字号和显示大小](system-type.md) |
| 打字和手写能否一键切换，小屏能否看清四个候选，字键实际有多高 | [紧凑工具栏和触控区域](compact.md) |
| 哪些宿主、系统版本、导航方式和无障碍场景还有待验证 | [兼容性矩阵与 50 个宿主测试场景](coverage.md) |
| 按键后多久能看到文字，首次打开键盘要等多久 | [输入延迟、启动、帧和内存](performance.md) |
| 无调拼写和连打能否找回目标词 | [词库与分词评测](lexicon.md) |
| 未参加训练的文献中，下一词和拼写补全能省几次点按 | [整篇留出与省键对照](prediction.md) |

## 山歌与词库

《山歌两首》第一首共有四行、22 次选词。互动 Demo 的三种自动播放分别核对整首记音汉字、带调号拼写和叶氏拼音。手机上的记事本录像另行记录实际按键和上屏结果。[逐词对照](../tools/story-demo/shange-spelling.tsv)

原图的 1,000 对音节有 175 个句首项。叶氏拼音与带调号拼写分别通过 1,000 项顺序核对。0.3.3 的设备用例检查点音节即可提交所选输出形式，并保持已确认词的上下文。

对 526 个词和词组逐条输入无调拼写，按共用词库返回的推荐字、汉义和消耗的输入长度计，首位命中 376 项，前三位命中 487 项，全部候选中可找到 526 项。手机把同写法的多种词义排在一格。464 组能与语料逐词对齐的相邻词都能在连打后分开选择。整句首选、严格词条 ID 和同形词表现见[分词评测](lexicon.md)。

## 复验安装包

GitHub 构建的调试包与 Releases 供手机升级的包使用各自的签名。设备测试记录运行包的 SHA-256。可以在对应版本的仓库目录下核对安装包内的完整词表、笔画模板、联想数据和四套原生库。

```sh
python3 qa/verify-apk.py tujia-keyboard-0.3.3.apk
./device-test/run.sh tujia-keyboard-0.3.3.apk
```

第二条命令需要已解锁的 Android 测试设备、JDK 和 Android SDK。也可以在 GitHub 运行 Android 15 设备工作流并下载测试证据。[构建与签名检查](../.github/workflows/android.yml) · [Android 15 设备测试](../.github/workflows/device-qa.yml)

## 毕资学堂离线版复验

在键盘仓库根目录运行下列命令。准备 JDK 17、Android SDK 35、NDK `28.2.13676358`、CMake `3.22.1`、Node 22 及一台已解锁的空白 AVD。用 `adb devices` 查出模拟器序列号，替换示例中的 `emulator-5554`。飞机模式和升级测试分别检查首次安装前的状态，并在结束时卸载自己的测试包。旧版系统切换飞行模式需要 AOSP 的 userdebug 镜像。

```sh
export ANDROID_SERIAL=emulator-5554
./gradlew :app:assembleTypingOnlyDebug :app:assembleHandwritingDebug
typing=app/build/outputs/apk/typingOnly/debug/app-typingOnly-debug.apk
hand=app/build/outputs/apk/handwriting/debug/app-handwriting-debug.apk
npm install --prefix build/qa-node --no-save --package-lock=false playwright@1.62.1
NODE_PATH="$PWD/build/qa-node/node_modules" node qa/learning-device.cjs "$ANDROID_SERIAL" "$typing" \
    ../bizic-dictionary/scripts/test-offline-mobile.cjs build/qa-reader
```

`bizic-dictionary` 路径应指向与内容快照对应的词典代码。测试在关闭网络、输入法尚未启用时安装 APK，退出未同意的首次隐私说明后重新启动，并确认默认关闭操作记录。接受后，测试在系统 WebView 中使用抽屉、设置与词典并保存整屏截图，最后复验同意状态在冷启动后仍然有效。Android 7 的原厂 WebView 按系统镜像可能为 Chromium 51 或 52，测试使用其支持的 DevTools 命令。设备指纹、浏览器版本、联网状态和逐项结果见 `build/qa-reader/results.json`。

跨应用输入测试由独立编辑器接收按键。`learning-ime` 涵盖冷启动候选、分词、两种拼写、模糊音、下词联想、个人词和敏感输入框。`typing-only` 检查候选布局并实测 IPA 补全。

```sh
./tools/test-core.sh
./tools/build-apk.sh --device-test build/qa-editor.apk
TUJIA_E2E_SUITE=learning-ime TUJIA_E2E_EVIDENCE=build/qa-ime \
    ./device-test/run.sh "$typing" build/qa-editor.apk
TUJIA_E2E_SUITE=typing-only TUJIA_E2E_EVIDENCE=build/qa-ime \
    ./device-test/run.sh "$typing" build/qa-editor.apk
TUJIA_E2E_SUITE=trial ./device-test/run.sh "$typing" build/qa-editor.apk
TUJIA_E2E_SUITE=hand-layout ./device-test/run.sh "$hand" build/qa-editor.apk
TUJIA_E2E_SUITE=hand-landscape ./device-test/run.sh "$hand" build/qa-editor.apk
```

私有存储测试在新装的测试包中开启操作记录，以合成输入核对密码、邮箱、网址、数字、电话以及禁止个性化的输入框均不会写入操作轨迹。它还检查关闭记录会清空轨迹，并验证三类个人词都可以跨实例重新读取。

```sh
adb install "$typing"
adb install build/qa-editor.apk
adb shell am instrument -w -r biz.tujia.ime.e2e/.PrivateStorageTest
adb uninstall biz.tujia.ime.e2e
adb uninstall biz.tujia.ime
```

升级复验用同一开发签名构建 `v0.3.6` 和当前版本，检查两条旧版升级路径以及新版两个变体间的双向替换。脚本写入测试词条、偏好和一份模拟崩溃记录，用 `adb install -r` 替换程序后逐项核对，并读取系统当前选中的输入法。它还分别复验新旧诊断偏好名中明确保存的开关值。可用 `--previous-typing` 加入上一个统一版 APK 并检查未设置时默认关闭。发布前还需用生产签名的候选 APK 验证 Releases 安装包的原位升级。

```sh
git fetch origin tag v0.3.6
git worktree add --detach build/qa-baseline v0.3.6
(cd build/qa-baseline && ./gradlew :app:assembleTypingOnlyDebug :app:assembleHandwritingDebug)
python3 qa/learning-upgrades.py --serial "$ANDROID_SERIAL" \
    --old-typing "build/qa-baseline/$typing" --old-handwriting "build/qa-baseline/$hand" \
    --typing "$typing" --handwriting "$hand" --fixture build/qa-editor.apk
```
