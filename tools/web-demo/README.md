# 离线互动键盘

网页将 `shared/src` 的 C++ 输入法编译为 WebAssembly。键盘使用与手机相同的词表、词频和后接词统计、音节对、音义证据和手写笔画。四行山歌的逐字回放只负责打键和选择已经存在的候选。

用 WASI SDK 25、Python 3 和当前仓库生成键盘。以下命令从仓库根目录运行。

```sh
mkdir -p build/web
cp -R tools/web-demo/ui/. build/web/
WASI_SDK_PATH=/path/to/wasi-sdk tools/web-demo/build-core.sh build/input.wasm
python3 tools/web-demo/embed-core.py build/input.wasm build/web/engine-data.js
```

打开 `build/web/index.html` 即可离线试打。要同时打包正字表、方言资料、图片和附表，把这套界面放入完整站点的 `dist` 目录，再运行以下命令。打包器会把脚本、词库和 WASM 内联到首页，以便解压后离线打开。

```sh
cp -R build/web/. /path/to/site/dist/
python3 tools/web-demo/package.py /path/to/site/dist build/artifacts --version 0.3.3
python3 tools/web-demo/check-offline.py build/artifacts/shange-demo.zip
```

只有代码仓库时，也可以从上一版公开发行包取出随书字表、图片和附录网页，离线重建整套演示。首页、键盘和预测始终使用当前源码。

```sh
curl -fL https://github.com/luggroo/bizic-keyboard/releases/download/v0.3.2/shange-demo.zip -o build/reference-appendix.zip
python3 tools/web-demo/package.py build/web build/artifacts --version 0.3.3 --reference-zip build/reference-appendix.zip
```

浏览器检查使用 Playwright 和 Chromium，在离线浏览器上下文中点击真实候选，并检查已写入的正文和剩余草稿。覆盖叶氏和带调号拼写、汉字记音、一次选入音节、短词选入后保留尾串、自由重组和山歌逐词回放。

键盘字体随浏览器默认字号变化。默认字号为 16 像素时，字母和候选为 19 像素，汉义为 14 像素。窄屏三字词会依照实际候选格宽适配。浏览器回归会检查 320 和 390 像素宽视口、85% 到 200% 的根字号、Chromium 字体偏好与页面缩放。

汉字记音的候选组采用共享词库的义项编号和实际消费长度。测试对照了无调 go、有调 gǒ 与叶氏 gor，同写「过」的“他”和“过错”，以及手写「尬」的两种意思。
