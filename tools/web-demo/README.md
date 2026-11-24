# 离线互动键盘

互动演示将共用输入引擎和手写模型打包进一个 `index.html`，解压后可直接在浏览器中打开。演示使用仓库内的词库、音节表、手写模型和笔画模板。

点「试写毕兹卡」，记事本会备好 `bizika`。三个键上方依次显示 `bifzixkar`、`bízīkǎ` 和「币子咔」。选好写法，再点带有“土家族”汉义的候选词即可上屏。自由输入时也能在键盘的「更多」里切换。

使用 WASI SDK 25、Python 3 和 onnxruntime-web 1.20.0 构建：

```sh
mkdir -p build/web
cp -R tools/web-demo/ui/. build/web/
WASI_SDK_PATH=/path/to/wasi-sdk tools/web-demo/build-core.sh build/input.wasm
python3 tools/web-demo/embed-core.py build/input.wasm build/web/engine-data.js
python3 tools/web-demo/embed-hand-model.py /path/to/onnxruntime-web/dist build/web
python3 tools/web-demo/package.py build/web build/artifacts --version 0.3.3
```

`build/artifacts/shange-demo.zip` 只包含 `index.html`。该文件内嵌模型、引擎、数据及许可说明，不需要网络连接。
