# NfcKeyRecovery

面向自有或已获授权 NFC 卡片的 Android 开源工具，支持 MIFARE Classic 密钥候选测试、读写备份、文件导入导出，以及可用卡片内容的保存与模拟。最低支持 Android 8.0，目标版本为 Android 16。

## 功能

- 识别 NFC 技术类型、UID、ATQA、SAK 和卡片容量。
- 对 MIFARE Classic Mini/1K/2K/4K 逐扇区测试 Key A/Key B 候选密钥，读取备份并在确认后写回同一 UID 的卡片；手机 NFC 后端不写入块 0。
- 导入和导出 `.keys`、`.txt`、`.mfd`、原始二进制与 JSON 数据。
- 通过 HTTPS 更新密钥源，支持一键拉取 Iceman/RRG 官方仓库的 `mfc_default_keys.dic`。可为自定义源配置 SHA-256 校验值。
- 保存普通 NDEF 标签或已解密 Classic dump 中的 NDEF 消息，并使用 Android HCE 作为 NFC Forum Type 4 NDEF 卡片模拟。
- 保存 MIFARE Classic 原始 dump 配置，供以后接入外部硬件；目前仅有 Proxmark3 USB 设备检测与适配接口，尚未实现硬件恢复或模拟指令。

**模拟限制：**Android HCE 只能响应 ISO-DEP/APDU，不能复现原卡 UID、Crypto-1 认证或 MIFARE Classic 扇区命令。保存并模拟 Classic dump 中的 NDEF 内容仅代表模拟相同的 NDEF 消息，不等于复制原卡。读卡器必须支持 NFC Forum Type 4 NDEF。手机需支持 HCE，模拟时保持解锁。

## 构建

需要 JDK 17、Android SDK 36 和相应的 Android 构建工具：

```shell
./gradlew test assembleDebug lintDebug
```

## 使用边界

仅测试本人拥有或明确获授权的卡片。内置密钥只包含公开的默认/测试密钥；一键下载的第三方字典由 RRG/Iceman 仓库维护，使用前请确认来源和适用范围。密钥与卡片配置保存在应用私有目录，并从系统备份中排除；显式导出的文件由用户自行保管。

## 许可证

GPL-3.0-or-later。
