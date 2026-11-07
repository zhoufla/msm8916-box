# JZ02 刷包 / 提权 / 回滚

## 为什么必须走 MiKo，而不是 fastboot

2026-11-07 真机实测：JZ02 原厂 aboot（LK）的 fastboot 实现极简，
`fastboot devices` 可见 `1a1143e fastboot`、`getvar max-download-size` = `0x10000000`（256MB）、
`getvar product` = `MSM8916`，但：

```
fastboot flash boot ...      -> Sending OKAY / Writing FAILED (remote: 'unknown command')
fastboot erase ...           -> FAILED (remote: 'unknown command')
fastboot boot ...            -> FAILED (remote: 'unknown command')
fastboot reboot / continue / oem reboot / oem poweroff
                             -> FAILED (remote: 'unknown command')
```

即**只实现 getvar / download / reboot 指令中的少数几条，不提供任何写分区入口**。
换用新旧两版 fastboot（33.0.2 / 32.0.0）结果一致，不是客户端版本问题。
本地 `一键刷机脚本工具/flash.bat` 能刷 Debian，是因为它先刷入 OpenStick 的 `aboot.bin`
替换了 bootloader；当前设备已被 MiKo 刷回原厂 aboot，故不可用。

**结论：分区写入一律走 MiKo 全量备份恢复（9008 EDL）。**

## 刷哪个包

| 包 | 内容 | 用途 |
|---|---|---|
| `ja02-fullbak.bin` | 原厂全量（只读基线） | 回滚 |
| `ja02-fullbak-slim.bin` | 标准档 171 项 | 已刷过，当前设备状态 |
| `ja02-fullbak-hardened.bin` | 强化档 223 项 | 精简但不带 root |
| **`ja02-fullbak-hardened-rooted.bin`** | **强化档 + rooted boot** | **本次推荐，一次到位** |

`ja02-fullbak-hardened-rooted.bin`
- size 3,909,074,432 B
- md5 `65507392b8c96bc67163e89da22ffc01`
- sha256 `81b5c1a8af494bc55bae3346d5b955e1384ced22a4092003a89f73515840fae2`
- GPT 头/分区项 CRC 有效；改动 4 个分区：`boot / system / userdata / recovery`；其余 23 个逐字节未变
- 原始包 md5 `2fa5ee48073018dd7afa0a9e546f242c` 前后一致

## rooted boot 到底改了什么

`work/boot_rooted.img`（16MB，md5 `546674c813e841a3f7bb805434eed1c0`）由原 `boot.img` 重打包而来：

- **只改 ramdisk 里的 `default.prop`**（207 B → 414 B）：
  - `ro.secure=1` → `0`  ：adbd 不再降权到 shell，adb shell 直接是 root，`adb remount` 可用
  - `ro.debuggable=0` → `1`
  - 新增 `ro.adb.secure=1` ：**保留 adb 公钥认证**
- kernel / second / dtb **逐字节未变**；51 个 ramdisk 文件逐一比对，只有 `default.prop` 不同
- 重打包脚本 `BAK-analysis/make_rooted_boot.py`，验证脚本内建

配合 `/data/misc/adb/adb_keys`（本机 `Zzhou@DESKTOP-0L7L114` 公钥，722B）与
`init.qcom.rc:180` 每次开机从 `/system/etc/adbkey.pub` 覆盖，效果是：

> **只有持有 `C:\Users\Zzhou\.android\adbkey` 私钥的电脑能连，连上就是 root。**
> 其它电脑仍会卡在 `unauthorized`，设备无屏也点不了确认框。

## 刷完自检

```
adb -L tcp:5039 devices -l          # 0  device product:UFI
adb -L tcp:5039 -s 0 shell id       # uid=0(root)
adb -L tcp:5039 -s 0 remount        # remount succeeded
adb -L tcp:5039 -s 0 shell "getprop ro.secure; getprop ro.adb.secure; getprop service.adb.tcp.port"
adb -L tcp:5039 -s 0 shell "ls /system/etc/security/otacerts.zip /system/vendor/speccfg/ota.zip"   # 应 No such file
```

## 注意事项

- **NV 标定属于本机**：包里含 `modem / modemst1 / modemst2 / fsg / persist` 与
  `/data/property/persist.sys.iccid`，只能刷回这台 JZ02。
- `recovery` 的 `/res/keys` 已清空 → 不能 `adb sideload` 任何签名 OTA；wipe data / MiKo 全量刷不受影响。
- 若设备当前停在 fastboot（本次实测就是），**拔插一次 USB 或长按电源键**即可正常开机；
  分区从未被写入，数据完好。MiKo 需要 9008 模式时按它的常规方式进入即可。

## 回滚

| 目标 | 操作 |
|---|---|
| 只去掉 root | MiKo 单独刷回原 `boot` 分区；或刷 `ja02-fullbak-hardened.bin` |
| 回到标准档 | MiKo 刷 `ja02-fullbak-slim.bin` |
| 完全原厂 | MiKo 刷 `F:\wifi\jz02\BAK\ja02-fullbak.bin` |

`BAK-analysis/work/live/rollback-system.bat` 是原先按 fastboot 写的回滚脚本，
**在本机 aboot 上不可用**，仅保留作参考，勿执行。

---

## 芷荷 aboot（解锁 fastboot）

> 用户提示：`F:\wifi\jz02\jz02刷机包以及工具\芷荷aboot.img`，
> 「之前都是先刷了这个 aboot」。2026-11-07 实测确认这个记忆是对的。

### 为什么原厂 aboot 什么命令都不认

二进制对比（1MB ARM ELF，头部一致、代码区差 402,576 B、签名证书 2023→2022）：

| 字符串计数 | 原厂 aboot | abootbak | 芷荷 aboot |
|---|---|---|---|
| `flash:` | **0** | 0 | **1** |
| `erase:` | **0** | 0 | **1** |
| `continue` | **0** | 0 | **1** |
| `oem ` | **0** | 0 | **5** |
| `reboot` | 4 | 4 | 6 |

原厂 aboot 把 `flash:` / `erase:` / `boot` / `oem` / `reboot` 命令处理整个移除了，
所以真机实测：

```
fastboot flash boot|aboot ...   -> Writing FAILED (remote: 'unknown command')
fastboot erase / boot / continue / oem reboot / oem poweroff / reboot
                                 -> FAILED (remote: 'unknown command')
```

新旧两版 fastboot（33.0.2 / 32.0.0）结果一致，不是客户端问题。
`fastboot devices` 可见、`getvar max-download-size=0x10000000`、`getvar product=MSM8916`。

### 能不能用 fastboot 刷芷荷 aboot

**不能**——`fastboot flash aboot 芷荷aboot.img` 同样是 `unknown command`：
当前运行的原厂 aboot 根本没有 `flash:` 处理函数，连给自己刷解锁版都做不到。
当年「先刷芷荷 aboot」必然是走 **MiKo / 9008 EDL** 写 `aboot` 分区
（9008 由 PBL 提供，不依赖 aboot，所以刷坏了也能救回来）。

### 推荐做法：一次刷入组合包

`ja02-fullbak-hardened-rooted-aboot.bin`（v4）在 v3 基础上把 `aboot` 分区换成芷荷版：

- 强化档 223 项精简 + 配置净化
- `ro.secure=0` → adb shell 直接 root、`adb remount` 可用（adb 公钥认证仍开）
- 芷荷 aboot → `fastboot flash/erase/reboot/oem` 全部可用
- `abootbak` **保留原厂镜像**作为回退

MiKo 一次刷完，之后改 /system 走 adb、需要刷分区走 fastboot，都不用再动 MiKo。

| 场景 | 包 |
|---|---|
| 完整方案（推荐） | `ja02-fullbak-hardened-rooted-aboot.bin` |
| 不要解锁 aboot | `ja02-fullbak-hardened-rooted.bin`（v3） |
| 不要 root | `ja02-fullbak-hardened.bin` |
| 回滚原厂 | `F:\wifi\jz02\BAK\ja02-fullbak.bin` |

校验值见 `F:\wifi\jz02\BAK-modified\校验和-强化档-rooted-aboot.txt`。

> 刷完设备若停在 fastboot，**拔插一次 USB 或长按电源键**即可正常开机。
> 芷荷 aboot 的签名证书与原厂不同，但 9008 EDL 不依赖 aboot，回滚始终可用。
