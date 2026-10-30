# 构建与发布

## 环境

- JDK 17（设置 `JAVA_HOME`）
- Android SDK Platform 35、Build Tools 35.0.0，按 Android Studio 提示接受 SDK 条款
- Android Studio 或项目自带 Gradle Wrapper（Gradle 8.11.1）

用 Android Studio 打开 `android/` 并同步依赖。命令行设置 `ANDROID_HOME`，或在 `android/local.properties` 写入 `sdk.dir`；该文件不会提交。

Windows PowerShell：

```powershell
./scripts/build-android.ps1 -Tasks @('testDebugUnitTest', 'lintDebug', 'assembleDebug', 'assembleDebugAndroidTest')
```

Linux / macOS：

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

调试安装包位于 `android/app/build/outputs/apk/debug/app-debug.apk`。
仪器测试须在可重置的模拟器上执行，入口为 `cn.examassist.app.AnswerOverlayCheck`；参数 `check` 可选 `bank`、`automatic`、`bank_ai`、`page_text`、`screenshot`、`overlay` 等。

## 正式签名

使用自己的稳定签名密钥，配置以下环境变量后运行 `assembleRelease`：

| 变量 | 内容 |
| --- | --- |
| `EXAM_ASSIST_KEYSTORE` | 密钥库绝对路径 |
| `EXAM_ASSIST_STORE_PASSWORD` | 密钥库密码 |
| `EXAM_ASSIST_KEY_ALIAS` | 密钥别名 |
| `EXAM_ASSIST_KEY_PASSWORD` | 密钥密码 |

未配置时 Gradle 只生成未签名 release。不要分发未签名包，也不要提交密钥或密码。密钥需要单独备份，同一应用后续更新应使用相同签名。

公开版本从 1.0.0 开始；`versionCode` 保持递增，用于 Android 安装更新，与公开版本号独立。

## GitHub 管理

`main` 保存可构建源码；修改通过 Pull Request，经 CI 测试、Lint 和编译后合并。版本使用 `v1.0.0` 形式的标签，APK 只放 GitHub Releases，附 SHA-256 校验文件；不把安装包放进 Git 历史。发布前核对版本、许可、敏感信息和签名。
