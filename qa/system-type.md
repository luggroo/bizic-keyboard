# 系统字号和显示大小

想调大候选词和键帽，可以先调手机的「字体大小」。想让按钮、图片和其他界面元素也占用更多屏幕空间，可以再调「显示大小」。两项各有自己的滑块，可以分别调整。在系统设置里搜索「字体」或「显示大小」，可以找到当前手机提供的选项。[Android 使用说明](https://support.google.com/accessibility/android/answer/11183305?hl=zh-Hans)

| 设备或系统 | 官方说明中的字号入口 |
| --- | --- |
| 小米 14T Pro | 设置 → 显示与亮度 → 字体设置。小米国际版的菜单名为 Display & brightness → Font settings。[小米 14T Pro 指引](https://www.mi.com/global/support/faq/details/KA-503495/) |
| 荣耀 MagicOS 10.0 | 设置 → 显示和亮度 → 字体设置。页面分别提供字体大小、字体和图标粗细的控制条。[荣耀指引](https://www.honor.com/cn/support/content/zh-cn15887696/) |
| 华为官方指引所列机型 | 设置 → 显示和亮度 → 字体大小与粗细。同一级的「显示大小」可同时调整应用中文字和图片的显示大小。[华为指引及适用机型](https://consumer.huawei.com/cn/support/content/zh-cn15920188/) |

这些入口按相应厂商、机型或系统版本列出。键盘的设备测试使用 AOSP 模拟器。厂商系统的字体替换、定制显示模式及具体机型的 APK 兼容性仍需真机确认。

## Android 14 及更高版本

Android 14 支持最高 200% 的字号。在较大的档位，系统会根据文字原来的大小采用不同的放大比例。例如，键帽和原本较小的汉义可以有不同的像素增幅。绘制文字时用 `sp`，自绘画布通过 `TypedValue.applyDimension(COMPLEX_UNIT_SP, …)` 换算像素。用同一个 `fontScale` 或 `scaledDensity` 乘所有字号会丢掉这条系统曲线。[Android 开发文档](https://developer.android.com/about/versions/14/features#non-linear-font-scaling)

测量时，在同一物理分辨率下分别改变字体档位和显示密度。保留相同拼写和候选，以截图核对实际字形，以控件边界核对可点击区域。提高显示密度后，还要核对四项候选、宿主正文和底行功能键能否同时出现。重新打开设置再返回会切换编辑器，可用 ADB 在键盘显示期间改系统档位，单独验证同一编辑框的待选拼写和未提交笔迹。

在 1080 × 1920 px 的 Android 15 模拟器上，保持显示密度 420 dpi，系统字号从 100% 调到 160% 后，Q 的可见字形从 44 px 增至 60 px，汉义「吃」从 32 px 增至 54 px。两种设置下 Q 的可点区域都高 45.7 dp。[九档字体与显示密度的设备测量和原图](typography.md)
