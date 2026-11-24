# 系统字号与显示大小

同一部手机上，两种设置带来不同变化。只调「系统字号」，字母、候选词和汉义随系统变大或变小。调「显示大小」后，每格可用的逻辑宽度也会变化，键盘随之重排。键盘在一般竖屏为字母和词形设定 20 sp，汉义为 14 sp，顶栏为 15 sp。[Android 布局源码](../app/src/main/java/biz/tujia/ime/TujiaIme.java) · [手机上的设置入口](system-type.md)

## Android 15 模拟器

Google Android 15、x86_64 云模拟器的物理屏幕为 1080 × 1920 px。在 420 dpi 下，屏幕宽 411 dp；提高系统显示密度到 540 dpi，宽度变为 320 dp。表中 Q 键高是实际可点区域；末三列是原始屏幕截图上 Q、「吃」和「架」的可见字形高度。[原始系统设置、包摘要与设备记录](results/api35-033-type-matrix.log)

| 系统字号 | 显示密度（dpi） | 宽度（dp） | Q 键高（dp） | Q 字形（px） | 吃（px） | 架（px） |
| --- | --- | --- | --- | --- | --- | --- |
| 85% | 420 | 411 | 45.7 | 37 | 27 | 42 |
| 100% | 420 | 411 | 45.7 | 44 | 32 | 50 |
| 115% | 420 | 411 | 45.7 | 47 | 39 | 55 |
| 130% | 420 | 411 | 45.7 | 52 | 44 | 59 |
| 160% | 420 | 411 | 45.7 | 60 | 54 | 69 |
| 200% | 420 | 411 | 47.6 | 74 | 61 | 86 |
| 100% | 540 | 320 | 46.2 | 56 | 44 | 63 |
| 160% | 540 | 320 | 46.2 | 77 | 70 | 88 |
| 200% | 540 | 320 | 47.7 | 96 | 79 | 109 |

「吃」和「架」的字号不同，Android 15 对两者的放大比例也不同。在同一 420 dpi 屏幕上，从 100% 调到 200%，系统给 14 sp 的换算从 36.75 变为 68.25 px，给 20 sp 的换算从 52.50 变为 89.25 px。每个字号的像素取自设备上的 `TypedValue.applyDimension`。[85%](results/api35-033-type-small.png) · [100%](results/api35-033-type-default.png) · [160%](results/api35-033-type-160.png) · [320 dp 窄屏、200%，完整显示「夹吓」](results/api35-033-type-largest-dense.png)

在 320 dp、200% 字号下，第三格「上面／夹吓」的两个汉字均可见。整行字形高 108 px，最右侧 28% 宽度内「吓」的笔画高 101 px。这个测量取自实际渲染画面。

九组均在外部编辑框逐键输入 `ga`，核对四个候选，点「吃／架」后读回「正文架」。收起再打开键盘后，输入框与正文仍可见。输入 `marmardi` 后在同一编辑框内先改字号、再改显示密度，草稿保留，之后可选择整词「麻麻爹」。该测试宿主自行处理系统配置变更。[改设置后保留的拼写](results/api35-033-type-draft.png)

Android 9 模拟器另有 480 × 800 px、160 dpi 的记录。将系统字号从 85% 调到 100%，字母 Q 的墨迹由 13 增至 16 px，Q 的可点高度均为 46 dp。这组旧模拟器下一档遇到了系统界面无响应。[Android 9 原始测量](results/api28-system-type-baseline.log)

## 复测

请在专用测试模拟器上运行。字号专项会临时修改系统字号和显示密度，退出时恢复原设置。

```sh
./tools/build-apk.sh ime.apk
./tools/build-apk.sh --device-test fixture.apk
TUJIA_E2E_SUITE=typography TUJIA_E2E_EVIDENCE=build/typography ./device-test/run.sh ime.apk fixture.apk
```

同轮测试在两个显示档实际点到键盘底行的「完成」，宿主均收到对应编辑动作及「正文架」。手写「三」等待自动收字时改变系统字号，第一个「三」进入正文。随后调整显示密度，手写区上缘由 1302 px 移到 1440 px；待新位置稳定后再写一个「三」，宿主读回「正文麻麻爹三三」，光标位于句末。[新手写区与第一个「三」](results/api35-033-type-hand-before-second.png) · [两次收字后的正文](results/api35-033-type-hand.png) · [完整通过记录](results/api35-033-type-matrix.log)

这轮测试从提交 `078a620` 构建 Android 15 测试 APK。各品牌手机的字体与显示档位还需对应实机复核。[工作流及原始截图](https://github.com/luggroo/bizic-keyboard/actions/runs/45446080562)
