# R答题助手

Android 本地题库与 AI 刷题工具，当前版本 **1.0.0**，支持 Android 8.0 及以上。手机独立运行，无需后端。

[下载安装包](https://github.com/556back/exam-assist/releases/latest) · [使用说明](android/app/src/main/assets/usage.txt) · [构建说明](docs/BUILD.md)

## 使用

1. 在「题库」导入文件，核对预览后确认；新安装题库为空。支持表格、文本、文档、PDF 和图片。
2. 在「AI 配置」填写 DeepSeek、通义千问或自定义接口的模型与 Key，并保存。仅用题库时关闭大模型解答。
3. 在「浮窗」开启并完成授权，点红色按钮获取答案并自动复制。主页右上角可随时打开离线使用说明。
4. 可选开启页面文字或全自动模式，需要无障碍授权；全自动选择答案并翻题，不自动交卷。

优先匹配启用的题库，无可用答案时才调用 AI。导入最多 10 MiB / 5000 题，PDF 最多 50 页。模型名称可编辑，以服务商账户实际可用模型为准。

## 开发

需要 JDK 17、Android SDK 35 和 Build Tools 35.0.0。

```powershell
./scripts/build-android.ps1 -Tasks @('testDebugUnitTest', 'lintDebug', 'assembleDebug')
```

也可用 Android Studio 打开 `android/`。项目保留通用回归测试；测试数据由代码构造，不随应用安装。

## 隐私

题库保存在本机，Key 使用 Android Keystore 加密。启用 AI 解答或题库整理时，相应文字和必要图片会发送给所选服务商；本机 OCR 使用 ML Kit。详见 [隐私说明](android/app/src/main/assets/privacy.txt)。请勿提交 Key、签名密钥、个人题库或设备日志。

## 许可

采用 [Apache-2.0](LICENSE)，允许修改、二次开发及商用；分发时须按许可保留版权、项目来源与 [NOTICE](NOTICE) 中的署名，标明修改。第三方组件的声明单独保存在 [third_party_licenses.txt](android/app/src/main/assets/third_party_licenses.txt)。
