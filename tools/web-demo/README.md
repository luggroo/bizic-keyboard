# 离线互动键盘

互动演示将共用输入引擎和手写模型打包进一个 `index.html`，解压后可直接在浏览器中打开。演示使用仓库内的词库、音节表、手写模型和笔画模板。

点「试写毕兹卡」后，可选叶氏拼音、带调号拼音或汉字谐音，再点“土家族”上屏。自由输入时也能在键盘的「设置」里切换。

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

更新调查点词表时，先以毕兹学堂的网站源码为输入，按词条及具体读音同步整词和词内借词成分，再重建北部词表：

```sh
python3 tools/prepare_survey_han_loans.py /path/to/bizic-dictionary/site
python3 tools/import_northern_words.py /path/to/bizic-dictionary/site
python3 qa/test_survey_loans.py
```

同步其他北部来源的已有注释时，先按网站项目的构建流程生成 `prepared` 数据，然后运行：

```sh
python3 tools/sync_reviewed_loan_forms.py /path/to/prepared-site /path/to/bizic-dictionary/docs
python3 tools/import_northern_words.py /path/to/prepared-site
```

词典中记为“可能借自”的整词也采用所注汉字；混合词只修改有明确借源的音节，其余选字和词义保留。已有词条的不同读音仍按各自证据处理。借源年代未定的汉语借词可使用本字，不据此改变词典对借入年代的标注。

`build/artifacts/shange-demo.zip` 只包含 `index.html`。该文件内嵌模型、引擎、数据及许可说明，不需要网络连接。
