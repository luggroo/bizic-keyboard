# 窄屏上的候选词和键位

在 AOSP Android 9（API 28、x86_64）上，点开外部编辑框后，「打字」「手写」「更多」「收起」位于同一行。输入 `ga` 时，下方四格同时显示候选，第一格上方是「吃」，下方是「架」。点它，宿主正文从「正文」变为「正文架」。打开再关闭「更多」会保留当前的 `ga`。切到手写后，改变手写输出格式不会移动书写区。[输入与候选](results/api28-033-compact-typing.png) · [更多](results/api28-033-compact-more.png) · [手写](results/api28-033-compact-handwriting.png)

系统字号设为 100% 时，一般竖屏字母使用 20 sp。480 × 800 px、160 dpi 的 Android 9 模拟器上，Q 的可点高度为 46 dp，默认字体的可见字形为 16 px。把系统字号调到 85% 后，Q 的字形变为 13 px，触区仍为 46 dp。[85% 与 100% 的原始测量](results/api28-system-type-baseline.log) · [九档系统字号与显示密度](typography.md)

输入 `rif`，点只写「日」的候选，宿主收到一个「日」，草稿清空。同一串拼写选「做／日」时，候选只用掉 `ri`，键盘仍显示 `f`，可接着输入。

## 三字候选的历次验证

以下两组截图来自早期固定大字号构建（APK 摘要 `25ff5973…`）。该构建的默认字母为 26 sp，在 480 dp 屏上的 Q 键高 54 dp。

在物理分辨率 480 × 800 px、显示密度 240 dpi 的模拟器上，整屏换算为 320 × 533 dp。系统设置 `font_scale=1.6`。键入 `marmardi` 后，第一格上方显示「女人」，下方完整显示「麻麻爹」，同排还有另外三个候选。点第一格，外部输入框得到「麻麻爹」，插入光标紧接最后一个字。打字区上方的空间较窄，较长草稿保留末尾几个字母。图中为原始分辨率截图。[选择前](results/api28-033-compact-three-han-320.png) · [选择后](results/api28-033-compact-three-han-320-after.png)

在较高的 320 × 720 dp 视口、160 dpi 和相同系统字号下，也逐字核过「麻麻爹」。这组 AVD 窗口覆盖设置所截屏幕有黑边，验字仅取第一格。两组都通过了候选两行文本与外部正文的断言，截图复核确认没有省略号。[屏幕设置、包摘要和原始断言](results/api28-033-compact-narrow.log)

## 复现三字词

将专用测试模拟器设成 320 dp 宽和 160% 系统字号，连接到 ADB。运行以下命令可实际逐键输入 `marmardi`，拍下有「女人／麻麻爹」的候选卡，点击该卡，并读回宿主中的完整文字。

```sh
./tools/build-apk.sh ime.apk
./tools/build-apk.sh --device-test fixture.apk
TUJIA_E2E_SUITE=long-word TUJIA_E2E_EVIDENCE=build/long-word ./device-test/run.sh ime.apk fixture.apk
```

候选截图是 `compact-three-han.png`，提交后的截图是 `compact-three-han-after.png`。`TUJIA_E2E_SUITE=compact` 另外检查四项工具栏、键位、宿主可见区以及 `rif` 的剩余拼写。
