# 恢复指南

[English](RECOVERY.md) | **简体中文**

安装、首次开机验证或区域切换未正常完成，或者需要安全卸载模块时，按本指南对应场景操作。

## 你遇到了哪种情况

| 现象 | 对应步骤 |
|---|---|
| 国区 / 全球区切换失败或中途中断 | [切换中断或失败](#切换中断或失败) |
| **首次安装**时 Magisk 报错，且**尚未**重启头显 | [首次安装尚未重启时报错](#首次安装尚未重启时报错) |
| 首次开机已进入系统，但面板提示首次安装待验证或验证失败 | [首次开机验证未完成](#首次开机验证未完成) |
| 安装模块后 Android 无法完成开机 | [头显无法完成开机](#头显无法完成开机) |
| 一切正常，想要卸载模块 | [正常卸载](#正常卸载) |

> [!CAUTION]
> **切勿手动删除 `/data/adb/pico-region-switch/headset-pending.json`、`first-install.json` 或任何区域数据银行目录**来「清除报错」。这些文件记录了原区域 APK、执行器摘要和准确的数据路径，是无损回滚的唯一依据。

---

## 切换中断或失败

切换过程中断时，后台执行器重启后会自动尝试回滚。你也可以直接在头显面板触发恢复：

1. 在头显应用库中打开 **区域切换**。
2. 点击 **恢复未完成的切换** 并确认。
3. 等待面板提示已恢复到切换前的原区域和应用。

事务日志（`headset-pending.json`）锁定了原执行器 APK 的 SHA-256 摘要、装包计划以及源/目标 APK 路径，恢复流程只会还原切换前状态，不会清空已保存的数据。

> [!NOTE]
> 如果面板提示存在未恢复的 PC 事务（`pending.json`），请使用 PC 端维护脚本（`region_switch.py`）完成恢复，不要用头显面板覆盖。

---

## 首次安装尚未重启时报错

首次安装准备阶段**不会**切区，也**不会**移动应用数据，重装 APK 使用的是原国区 APK 的相同字节。若准备中途失败，设备会保留 `/data/adb/pico-region-switch/first-install.json` 以及 `/data/adb/pico-region-switch/headset/first-install-<id>` 备份目录。

1. 原系统仍在运行时，**不要重启头显**。
2. 通过 root Shell 检查是否生成了安装日志：
   ```sh
   adb shell su -c "cat /data/adb/pico-region-switch/first-install.json"
   ```
   - **如果 `first-install.json` 不存在**：说明安装器在预检阶段就中止了，未改动任何应用或文件。保留 Magisk 报错信息核对前置条件或 APK 摘要即可，无需额外清理。
   - **如果 `first-install.json` 存在**：记下其中 `"run"` 字段的完整路径（例如 `/data/adb/pico-region-switch/headset/first-install-<id>`）。
3. 将仓库中的 [`tools/restore-first-install.sh`](tools/restore-first-install.sh) 推送到头显，并以 root 身份传入上述完整备份目录运行：
   ```sh
   adb push tools/restore-first-install.sh /data/local/tmp/restore-first-install.sh
   adb shell su -c "sh /data/local/tmp/restore-first-install.sh /data/adb/pico-region-switch/headset/first-install-<id>"
   ```

脚本会调用该备份目录内保存的 `executor.apk`（核对其 SHA-256 与日志一致），校验原 APK 和数据根目录，恢复应用运行状态与备份的运动中心解析缓存，并只清理未完成的初始化标记。日志、原国区 APK 和完整数据 TAR 都会保留；后续重新安装时会自动归档这次已恢复的日志。

---

## 首次开机验证未完成

首次重启后，后台执行器会核对以下状态：

- 新的内核启动 ID（`boot_id` 相对准备阶段已变化）
- 已加载的修补框架（`services.jar`）与运动中心底包映射
- 保留的原国区 APK、备份摘要、应用数据根目录与核心服务健康度

在验证通过前，面板会锁定切区按钮，避免在未就绪环境下操作。

1. 开机后等待 30–60 秒让系统服务稳定，再打开 **区域切换**。
2. 点击 **验证首次安装**。

该按钮只会重新执行校验（并在校验通过后补齐未写完的支持标记），不会重装应用或自动切区。

---

## 头显无法完成开机

本模块不改动引导程序（bootloader）或系统分区，所有框架与运动中心替换都由 Magisk 动态挂载。因为首次安装已提前将相同字节的原国区 APK 重装为数据更新包，停用模块后即可干净退回原系统框架，不会让全球版运动中心读到国区数据。

1. 通过 Magisk 安全模式 / Recovery 机制，或在可用的 root ADB Shell 中停用 `pico-region-downgrade` 模块后重启：
   ```sh
   adb shell su -c "touch /data/adb/modules/pico-region-downgrade/disable"
   adb reboot
   ```
2. 回到原系统框架正常开机后，读取 `/data/adb/pico-region-switch/first-install.json` 中的备份目录，按 [首次安装尚未重启时报错](#首次安装尚未重启时报错) 运行 `restore-first-install.sh` 撤销准备。
3. 如暂不打算重试，可在 Magisk 中移除已停用的模块。

> [!IMPORTANT]
> **不要通过恢复出厂设置或删除数据银行来解决框架映射引起的开机问题。** 停用 `pico-region-downgrade` 后，下一次重启即立即恢复未修改的系统分区。

---

## 正常卸载

> [!WARNING]
> 当前处于全球区时，切勿直接在 Magisk 停用或删除模块。务必先在面板完成 **准备卸载模块**。

1. 退出 **Virtual Desktop**，打开 **区域切换**。
2. 点击 **准备卸载模块** 并确认。
3. 等待面板提示已恢复完整国区并完成运动中心解析缓存处理。
4. 打开 **Magisk -> 模块**，移除 `pico-region-downgrade`，然后重启一次头显。

卸载会保留当前的国区应用数据，不会把设备回退到早前的旧快照；两区数据银行和恢复归档也会继续保留在磁盘上。
