# CI 与发布流程

[English](RELEASING.md) | **简体中文**

本仓库通过 [`.github/workflows/ci.yml`](.github/workflows/ci.yml) 在每次推送和 Pull Request 时运行无特权验证，并仅在版本标签触发时构建和发布签名后的完整离线 Magisk 模块。

## 持续集成（CI）

每次推送和 Pull Request 都会在 `ubuntu-24.04` 上运行 `test` 任务：

1. 运行 Python 单元测试和公开源码契约检查（`tests/`）。
2. 拉取锁定的主机端测试依赖（`tools/dependencies.py`）。
3. 运行 JVM 故障注入测试，编译测试版面板 APK 和 arm64 `region-rename` 二进制文件。
4. 上传 `runtime-ci-only` 产物（包含 `PicoRegionSwitch.apk`、`region-rename` 和 `host-tests.txt`）。

Pull Request 任务不会接触签名密钥或官方二进制输入密钥。`runtime-ci-only` 仅供 CI 核查，不是可安装的完整模块。

---

## 首次配置发布环境

在 GitHub 仓库中创建名为 `release` 的环境（可按需配置审核人，在正式发布前暂停等待人工批准），并设置以下 4 个 Secret：

| Secret | 说明 |
|---|---|
| `REGION_INPUTS_URL` | 存放官方输入压缩包的 HTTPS 地址（可使用私有签名链接） |
| `PANEL_KEYSTORE_B64` | 用于签名 `PicoRegionSwitch.apk` 的私有 `.jks` 密钥库 Base64 编码 |
| `PANEL_STORE_PASSWORD` | 密钥库与密钥密码 |
| `PANEL_KEY_ALIAS` | 签名别名（通常为 `panel`） |

### 打包官方输入归档

在 Git 仓库外部打包 25 个锁定文件，上传到 `REGION_INPUTS_URL` 指向的 HTTPS 地址：

```sh
python tools/inputs.py pack --directory build/inputs --archive build/vendor-inputs.zip
```

`inputs.py pack` 会按 [`config/inputs.lock.json`](config/inputs.lock.json) 逐一校验并只打包这 25 个文件，不包含任何账号数据、设备日志或签名密钥。Fork 仓库无需此归档即可运行 CI，只有构建完整发布包时才需要。官方二进制的分发许可独立于本仓库的 MIT 源码许可。

---

## 正式发布步骤

1. 更新 [`version.json`](version.json)、[`CHANGELOG.md`](CHANGELOG.md) 和 [`CHANGELOG_zh.md`](CHANGELOG_zh.md)，并在本地运行测试及使用稳定签名密钥完成一次本地完整构建。
2. 推送标签 `v<version>`（或通过 `workflow_dispatch` 手动指定已有标签；工作流始终检出 `refs/tags/<tag>`，而不是分支最新提交）。
3. `test` 任务通过后，受 `release` 环境保护的 `module` 任务会执行以下步骤：
   - 核对标签与 `version.json` 及中英文变更日志一致。
   - 下载并校验 `REGION_INPUTS_URL`，重建并审计 `services.jar`，复跑 JVM 测试，使用 `PANEL_KEYSTORE_B64` 签名面板（并在 `always()` 步骤中删除临时密钥库）。
   - 调用 `tools/verify.py` 校验全部载荷文件，并通过 `tools/release.py` 整理发布附件。
4. 独立的 `publish` 任务（唯一拥有 `contents: write` 权限的任务）先创建 **Draft** 状态的 GitHub Release，上传全部校验通过的附件，确认上传无误后再公开。

### 发布附件与防误覆盖机制

| 文件 | 说明 |
|---|---|
| `PicoRegionSwitch-<version>.zip` | 完整的离线可安装 Magisk 模块 |
| `module-manifest.json` | 模块内全部文件的清单与 SHA-256 摘要 |
| `build-result.json` | 构建身份与输出摘要记录 |
| `LICENSE` | 自有源码的 MIT 许可证 |
| `THIRD_PARTY_NOTICES.md` | 官方二进制与构建依赖许可声明 |
| `SHA256SUMS.txt` | 全部发布附件的 SHA-256 校验和 |

- Release 说明由 `CHANGELOG.md` 和 `CHANGELOG_zh.md` 中匹配的 `[<version>]` 章节自动拼装生成。
- 含 `-` 的标签（如 `v0.6.0-dev.1`）会标记为 GitHub 预发布。涉及新安装路径的改动应保持在预发布阶段，直到实机验收完成。
- 工作流不会自动覆盖已存在的 Release；若上传中途失败，需人工检查并清理残留的草稿后再重试。
