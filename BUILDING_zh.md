# 构建指南

[English](BUILDING.md) | **简体中文**

本指南介绍如何运行源码与主机端测试、编译运行时面板与原生辅助程序，以及组装完整的离线 Magisk ZIP。

## 环境要求

支持 Windows x64 和 Linux x64 工具链。请在仓库根目录执行所有命令，并每次指定**新的** `--output` 目录以保留历史构建产物。

| 工具 | 版本 / 组件 | 环境变量 |
|---|---|---|
| Python | 3.11 及以上 | — |
| JDK | 17 | `JAVA_HOME` |
| Android SDK | `platforms;android-35`、`build-tools;35.0.0`、`ndk;26.1.10909125` | `ANDROID_HOME` |

---

## 源码测试与运行时构建

无需官方二进制输入即可完成开发调试和 CI 验证：

```sh
python -B -m unittest discover -s tests -v
python tools/dependencies.py
python tools/build.py --output build/runtime
```

`tools/build.py` 会运行 JVM 事务、`Engine` 与 `FirstInstall` 故障注入测试，编译 `PicoRegionSwitch.apk`，并交叉编译 arm64 架构的原生目录原子替换工具 `region-rename`。

> [!NOTE]
> 未传入 `--inputs` 时，`tools/build.py` 会生成仅用于测试的临时 CI 签名密钥。单独产出的 `PicoRegionSwitch.apk` 并不是完整的可安装模块。

---

## 完整离线 Magisk ZIP

构建可安装的完整离线模块需要提供 [`config/inputs.lock.json`](config/inputs.lock.json) 中锁定的 25 个官方文件，按以下结构放在输入目录（例如 `build/inputs`）：

```text
build/inputs/
├── cn/           # 8 个国区 APK（matrix、store、user_center、fitness、friends、home、avatar、swift）
├── global/       # 8 个全球区 APK（Matrix 6.3.4、Store 4.9.0，以及 6 个已审计的 5.13.8 全球区 APK）
├── stock-cn/     # 8 个 OS 5.13.7 固件原装国区 APK（仅用于身份基线校验，不打包进 ZIP）
└── services-original.jar   # 国区 OS 5.13.7 原版 /system/framework/services.jar
```

身份锁文件固定了精确的 SHA-256 摘要和字节大小，不提供在线自动下载服务。官方固件可通过 PICO 常规渠道获取；保留的全球区 Matrix `6.3.4` 与 Store `4.9.0` 参考归档位于 [3-2026 应用合集](https://github.com/owomushi/Pico-Region-Switcher-Apps/releases/tag/3-2026)。构建前会逐一核对所有输入文件的摘要和官方平台签名。

### 构建命令

1. 在环境变量中设置签名密码（keystore 密码与 key 密码须一致，`tools/build.py` 不会在日志中打印密码）：
   ```sh
   export PANEL_STORE_PASSWORD="your-keystore-password"
   ```
   任何对外分发版本都应使用**长期保留的私有签名密钥**，以保证面板升级时签名身份一致。
2. 校验输入、拉取框架构建依赖、构建完整模块并执行产物校验：
   ```sh
   python tools/inputs.py check --directory build/inputs
   python tools/dependencies.py --framework
   python tools/build.py --output build/module --inputs build/inputs --keystore /path/to/panel.jks --key-alias panel
   python tools/verify.py --build build/module
   ```

构建过程中会基于 `services-original.jar` 重建修补版 `services.jar`，执行零改动对照、方法语义比对和原始 ZIP 条目保留检查，确保最终框架与审计通过的补丁摘要一致。产出的 Magisk ZIP 采用固定时间戳，并同步生成 `module-manifest.json` 与 `build-result.json`。

---

## 版本管理

- 修改 [`version.json`](version.json) 中的 `version` 和 `version_code` 来更新 APK 与模块版本号。
- 打标签前，须在 [`CHANGELOG.md`](CHANGELOG.md) 和 [`CHANGELOG_zh.md`](CHANGELOG_zh.md) 中补齐对应的 `[<version>]` 章节。
- 发布标签必须与 `v<version>` 严格一致；带有连字符后缀的标签（如 `v0.7.0-dev.1`）会自动发布为 GitHub 预发布（Prerelease）。
