# 离线互动键盘

互动演示将共用输入引擎和手写模型打包进一个 `index.html`，解压后可直接在浏览器中打开。演示使用仓库内的词库、音节表、手写模型和笔画模板。

键盘显示候选时，可以用实体键盘的 `1`–`4` 选择当前页的候选。主分支更新后会自动重新构建演示并更新最新发布的下载包。

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
