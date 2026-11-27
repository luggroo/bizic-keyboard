# 0.4.0 未签名正式构建

这四个 APK 尚未签名，**不能安装，也尚未公开发布**。

- `土家语输入法-纯输入法-unsigned.apk`：包含输入法、手写与输入法设置，不含学堂页面资料或学堂词典索引。
- `土家语输入法-纯输入法-无手写-unsigned.apk`：仅包含打字输入法及设置，不含学堂和手写。
- `毕兹学堂-全功能-unsigned.apk`：包含学堂、词典、语言资料和手写输入法。
- `毕兹学堂-全功能-无手写-unsigned.apk`：包含学堂、词典和打字输入法，不含手写。

四包均为 `biz.tujia.ime`，版本号 `0.4.0`、versionCode 15，面向 Android 7 及以上；均不申请网络权限。它们是同一应用的四个替代包。签名后相互覆盖安装需要同一签名证书，用户数据仍应通过实际升级测试核验。

四包按 16 KB 原生库对齐规则通过 zipalign 检查。SHA-256 见 `SHA256SUMS`。源码提交为 `323cf0c19cf3cbe9152ac7a4bcd41168de7f9e10`，学习资料来源为词典提交 `e75d9dd7bde754e3140da3da101896401568e820`。

## 后续签名与发布

在持有正式证书的安全环境中，使用 Android SDK Build Tools 的 `apksigner` 对四个包分别签名；不要在命令或聊天中写入证书密码。签名前无须改动已经对齐的 APK，签名后用 `apksigner verify --verbose --print-certs` 验证四个包。

要覆盖安装已发布的 0.3.6，四个包的签名证书 SHA-256 必须为：

`76a10e80a87bd4a6e5eab23767a303e5c8f7652eaaaa741df10f4c905f20773b`

只有匹配原证书后，才能在测试设备上验证 0.3.6 的覆盖升级、个人词库、设置及输入法选中状态，再安排正式发布。不要通过卸载已安装版本来绕过签名不匹配，卸载可能删除用户数据。

此前的发布工作流要求预先签好的 APK 和其他发布附件；当前工作流不会自动使用 GitHub Actions 密钥对这些构建签名。此次仅准备文件，没有推送源码或创建公开 Release。

## 构建

在固定 Android 工具链及依赖可用时，于键盘仓库执行：

```sh
./gradlew clean :app:assembleHandwritingRelease -PkeyboardOnly=true
# 保存 app/build/outputs/apk/handwriting/release/app-handwriting-release-unsigned.apk
./gradlew clean :app:assembleHandwritingRelease
# 保存同一路径下的全功能 APK
./gradlew clean :app:assembleTypingOnlyRelease -PkeyboardOnly=true
# 保存 app/build/outputs/apk/typingOnly/release/app-typingOnly-release-unsigned.apk
./gradlew clean :app:assembleTypingOnlyRelease
# 保存同一路径下的学堂无手写 APK
```

各包的权限、包名及无签名状态已检查；两个无手写包未包含手写模型或 ONNX 库。当前执行环境中的 Android 模拟器未能完成启动连接，因此这次新建的纯输入法启动及签名后的升级流程仍需设备测试。
