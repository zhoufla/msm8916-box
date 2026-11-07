# JZ02 刷包 / 提权 / 回滚

<!-- v5-verified-2026-11-07 -->
> **⚠️ 2026-11-07 实机更正（必读）**：v3 / v4 只改 `default.prop` 的 `ro.secure=0`
> **并不能**让 JZ02 拿到 root。JZ02 原厂 `/sbin/adbd` 是 **user 版编译**（二进制里
> 没有 `ro.secure` 字符串、只有 `adbd cannot run as root in production builds`），
> `should_drop_privileges()` 恒为 1，无条件降到 `AID_SHELL`，根本不读 `default.prop`。
> `adb root` 虽返回 `restarting adbd as root`、`service.adb.root=1`，重启后仍是 `shell`。
>
> 唯一有效的是 **v5**：把 SP970 的 eng 版 `/sbin/adbd`（静态 ELF32 / ARM EABI5、
> 含 `ALLOW_ADBD_ROOT`、仍带 `ro.adb.secure` / `adb_keys` 认证）换进 boot ramdisk，
> 再配 `ro.secure=0` + `ro.adb.secure=1` → root 与公钥认证同时成立。
>
> 当前真机已刷 v5、全项自检 16/16 PASS、适配层已部署并热重启验证通过。
> 详见文末「v5 修复与真机验证结果」。**请勿再刷 v3 / v4 当最终包。**

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
| **`ja02-fullbak-hardened-rooted-aboot-v5.bin`** | **强化档 + root 版 adbd + 解锁 aboot** | **本次推荐，一次到位** |

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
| 完整方案（推荐） | `ja02-fullbak-hardened-rooted-aboot-v5.bin`（v5，含 root 版 adbd） |
| 不要解锁 aboot | `ja02-fullbak-hardened-rooted.bin`（v3） |
| 不要 root | `ja02-fullbak-hardened.bin` |
| 回滚原厂 | `F:\wifi\jz02\BAK\ja02-fullbak.bin` |
| 回滚 v4（无 root） | `ja02-fullbak-hardened-rooted-aboot.bin` |

校验值见 `F:\wifi\jz02\BAK-modified\校验和-强化档-rooted-aboot.txt`。

> 刷完设备若停在 fastboot，**拔插一次 USB 或长按电源键**即可正常开机。
> 芷荷 aboot 的签名证书与原厂不同，但 9008 EDL 不依赖 aboot，回滚始终可用。

---

## 九、v5 修复与真机验证结果（2026-11-07）

<!-- v5-verified-2026-11-07 -->

### 1. 根因：JZ02 的 adbd 是 user 版，不读 `ro.secure`

| 项 | JZ02 原厂 `/sbin/adbd` | SP970 `/sbin/adbd` |
|---|---|---|
| 大小 | 161,956 B | 166,188 B |
| 链接方式 | 静态 ELF32 / ARM EABI5，零 `DT_NEEDED` | 同左（同 SoC，可直接移植） |
| `ro.secure` 字符串 | **无** | **有**（编译了 `ALLOW_ADBD_ROOT`） |
| `ro.adb.secure` / `adb_keys` | 有 | 有 |
| 结果 | 无条件降权 shell | `ro.secure=0` 时保持 root |

`init.rc:504` 是标准 `service adbd`（无 `user shell` 覆写），所以问题在二进制本身，
不在 `.rc` 或 `default.prop`。

### 2. v5 交付（`BAK-analysis/make_rooted_boot_v5.py` + `build_fullbin_v5.py`）

| 文件 | md5 | 说明 |
|---|---|---|
| `work/boot_rooted_v5.img` | `c741af6f5d1d9612573296dcb8fa2925` | 16MB，只换 ramdisk 里 `/sbin/adbd` + `default.prop` |
| `BAK-modified/ja02-fullbak-hardened-rooted-aboot-v5.bin` | `2a6e9cfcc093f1691e57918e9fda0c09` | 3.7G 整包；相对 v4 **只有 boot 分区不同** |

校验：52 个 cpio 条目元数据不变、仅 2 个 payload 变化；kernel / second / dtb 逐字节一致；
GPT 头与分区项 CRC 有效；`abootbak` 仍是原厂回退；其余 22 分区逐字节一致；
原始包 md5 `2fa5ee48073018dd7afa0a9e546f242c` 始终未变。

### 3. A 方案快刷（已实机走通）

当前 aboot 已是芷荷解锁版（`fastboot oem device-info` 有完整输出、`version: 0.5`），
不必再走 MiKo 全量刷：

```
adb -L tcp:5039 -s 0 reboot bootloader
fastboot flash boot F:\wifi\jz02\BAK-analysis\work\boot_rooted_v5.img
  -> Sending 'boot' (16384 KB) OKAY / Writing 'boot' OKAY [2.647s]
fastboot reboot                        -> Rebooting OKAY，7 秒后 adb 上线
```

若 `flash` 返回 `unknown command`，说明 aboot 仍是原厂（命令不会写入任何数据），
改用 MiKo 9008 刷 v5 整包。**两条路都不会破坏回滚基线**（v4 整包 + 原包都在）。

### 4. 刷后自检：`work/live/verify-hardened.py` → **16/16 PASS**

`uid=0(root)`、`ro.secure=0` / `ro.debuggable=1` / `ro.adb.secure=1`、
`adb remount succeeded`、两处 adb 公钥 = 本机 `Zzhou@DESKTOP-0L7L114`、
`copy_apps.sh` 有效命令只剩 `exit 0`、24 个删除路径 GONE、8 条通信/面板红线 EXISTS、
quipc / xtwifi / gps / SwimConfig 外联全断、
**aboot 分区读回 md5 = `b49068f842744314342b362d3b58779f`（芷荷版确认）**。

### 5. 适配层真机部署（已全部完成）

```
adb -L tcp:5039 -s 0 install -r dist/com.sp970.smsrelay-1.7.0.apk   -> Success
python tools/jz02/deploy-jz02.py                                      -> 6/6 + 7 项校验全 OK
python tools/jz02/deploy-jz02.py --verify-only --reboot               -> 重启后校验：全绿
```

- busybox 396 applets → `/system/xbin/busybox`，补齐 `/system/etc/passwd`
- relay-helper（token `jz02-relay-2026`）→ `/system/etc/relay-helper`
- `init.qcom.post_boot.sh` 幂等注入 shebang 之后，`sh -n` RC=0，原文件备份为 `.pre-helper`
- 8899 只听 `127.0.0.1`，`/ping.txt` = pong，命令通道 `uid=0(root)`，错误 token `exit=77`
- 看门狗 / crond ALIVE；热重启后自启生效
- **8080 空闲**（厂商面板只占 `:::80`），适配层默认端口不冲突
- 网络 adb：设备侧 `0.0.0.0:5555` 已 LISTEN、`service.adb.tcp.port=5555`；
  本机直连 `192.168.100.1:5555` 被 reset（主机不在 JZ02 热点网段），USB adb 不受影响

### 6. 仍待确认（与精简无关）

- `SIM READY` / 中国电信、`numeric` 空、`mServiceState=1` OUT_OF_SERVICE：
  精简前后一致，不是精简造成；需要时做原包 / 修改包 A/B 定位。
- 无 Mms 时 `content://sms` 读写是否完全可用（需插卡注网后测）。
- 主机接入 JZ02 热点后网络 adb 的可达性；不需要网络 adb 时把
  `post_boot.snippet` 里两行 `setprop` 注释掉即可。

---

## 十、v6：厂商应用清零 + SP970 桌面（2026-11-07）

<!-- v6-vendor-strip-2026-11-07 -->

### 1. 为什么「桌面实在必须」

强化档之后系统里只剩 32 个 APK，其中**唯一真正的厂商定制应用**是
`com.youdo.server` = `/system/priv-app/ufilauncherzx.apk`（8,518,315 B，
`com.stub.StubApp` 加固壳）：persistent 进程、声明 `HOME`、跑
`com.youdo.ufi.ui.UfiServer` 占 80 端口、带 `BOOT_COMPLETED` 优先级 1000 的 receiver。

删掉它之后实测：

```
init.svc.bootanim = running        (永不退出)
sys.boot_completed = (空)          (BOOT_COMPLETED 不广播)
AMS mFocusedStack = null           (没有任何 HOME activity)
```

**JZ02 虽然无触摸屏（`-touch -keyb -nav`），仍必须有一个 HOME**，
否则 bootanimation 不结束、`sys.boot_completed` 不置 1，
`init.qcom.post_boot.sh` 的注入片段和 relay-helper 自启都不会触发。
装回 HOME 后 20 秒即 `boot_completed=1` / `bootanim=stopped`。

### 2. 精简内容

| 类别 | 处理 |
|---|---|
| 厂商应用 | 删 `/system/priv-app/ufilauncherzx.apk`；PMS 重启后移除 `com.youdo.server` |
| 厂商数据 | 删 `/data/data/com.youdo.server`、`/data/SelfHost`、`/data/switch_spec` |
| 运营商规格空壳 | 删 `/system/vendor/{CTA,ChinaMobile,ChinaTelecom,ChinaUnicom,overlay}`（均 0 文件） |
| 切规格脚本 | `speccfg/switch_spec.sh` 短路为 `exit 0`（原脚本含 `wipe data`，且模板已删空）；原件留 `.pre-slim` |
| dalvik | 清 `*ufilauncherzx*` / `*youdo*` 缓存 |

`/data/fota` 是开机时由系统以 `system:system` 重建的 FOTA 占位空目录，
**不是厂商应用残留**，保留且不列入清理断言。

### 3. 严格保留（删了会砖或影响插卡上网）

`/system/vendor/Default`（含 `data/misc/radio/qcril.db`）、
`speccfg/vendor.prop` / `speccfg/spec` / `copy_apps.sh`、
全部高通 `/system/vendor/lib`（459 个文件，相机/GPU/音频）、
`android.policy.odex` / `services.odex`（framework 核心，内含厂商定制但不可删）、
`TeleService` / `TelephonyProvider` / `ims` / `Settings` / `apns-conf.xml`、
relay-helper 适配层、adb 公钥、芷荷 aboot / abootbak。

### 4. 桌面来源（用户指定从 SP970 提取）

| 文件 | 大小 | md5 | JZ02 目标 |
|---|---|---|---|
| SP970 `/system/priv-app/Launcher3.apk` | 3,198,370 B | `fd5a5d448f8e7f56c2eb4db3bf6041ef` | `/system/priv-app/Launcher3.apk` |
| SP970 `/system/app/Launcher2LayoutRes.apk` | 7,229 B | `cbf1c646bdd69717a76aa7d1ab9a3bd2` | `/system/app/Launcher2LayoutRes.apk` |

同为 Android 4.4.4 / msm8916、纯 APK 无 odex、AOSP 标准
`com.android.launcher3`，可直接移植；本目录 `launcher/` 留有副本。
厂商面板 APK 备份 `work/live/ufilauncherzx.backup.apk`
（md5 `1e4c5b165e87b704aae84f6cef27911f`）用于 `--restore`。

### 5. 脚本

`F:\wifi\jz02\BAK-analysis\work\live\strip-vendor.py`（本目录 `strip-vendor.py` 为副本）：

```
python strip-vendor.py --with-launcher --reboot   # 本次实际执行
python strip-vendor.py --restore --reboot         # 完整回滚
```

注意：`adb remount`（宿主机客户端请求）才能把 `/system` 挂 rw，
设备内 `mount -o remount,rw /system` 在此 ROM 上不生效。

### 6. 验证结果

`work/live/verify-hardened.py` **20/20 PASS**：
root / 公钥 / 强化档 24 项 GONE / 通信红线 7 项 / 厂商残留全 GONE /
`com.youdo.server` 已从 PMS 移除 / HOME=`com.android.launcher3` /
开机流程完成 / `switch_spec.sh` 短路 / 4 份配置净化 / aboot 芷荷版。

开机后：80 端口空出、8080 空闲、`127.0.0.1:8899` helper、
`0.0.0.0:5555` 网络 adb；`deploy-jz02.py --verify-only` 全绿。

### 7. 可选的下一阶段（尚未执行，需确认）

无屏设备用不到的 AOSP/UI 应用可继续清，但**通信链必须保留**：
`Contacts / ContactsProvider / Dialer / Keyguard / SystemUI / Provision /
PackageInstaller / Bluetooth / BluetoothExt / CertInstaller / KeyChain /
InputDevices / ExternalStorageProvider / MediaProvider / OneTimeInitializer /
ProxyHandler`。
其中 `Keyguard` / `SystemUI` 删除需重测开机流程（它们与 HOME/bootanim 相关），
`PackageInstaller` 删除后只能靠 `adb install` / `pm install`。

### 8. v6 可复刷交付（校验值）

<!-- v6-checksums-2026-11-07 -->

| 文件 | md5 | 用途 |
|---|---|---|
| `BAK-modified/ja02-fullbak-hardened-rooted-aboot-v6.bin` | `c57b5ed8611528bcdf69a4b27d65370e` | MiKo 9008 全量刷（3.7G） |
| `BAK-analysis/work/system_v6.img` | `bf74c42981e0b2d1073a2a93db3ec706` | raw ext4 800MB，`e2fsck -fn` 全清 |
| `BAK-analysis/work/system_v6.sparse.img` | `20e826bcb9af4b5496baae6553546bff` | `fastboot flash system`（4 chunks，各 ≤200MiB） |

v6 整包 sha256 `56fbb6a61f5bc9b651c24b996e3ec7f72be60fe90c230a0fa59e4b5b771baf3e`；
GPT 头/分区项 CRC 有效、22 个分区逐字节未变、原始包 md5 `2fa5ee48…` 全程未动。
`system_v6.img` 是真机 dd 快照经 `e2fsck -fy` 清理 orphan inode 后的干净镜像。

刷 v6 整包后需重新装一次 APK（APK 在 `/data/app`，不在 system 分区）：
`adb -L tcp:5039 -s 0 install -r dist/com.sp970.smsrelay-1.7.0.apk`。
helper 自启已固化在 system 的 post_boot 注入里，开机自动生效。


<!-- jz02-panel-fix-2026-11-07 -->

## 十一、8080 面板可用性（2026-11-07 真机验证与修复）

**症状**：`http://192.168.42.129:8080/` 打不开。主机 `192.168.42.32` 能
ping 通设备 `192.168.42.129`，但 TCP 8080 无监听。Windows 上用
`Invoke-WebRequest` 看到的 `502 Bad Gateway` 是**系统代理**返回的，不是设备；
直连请用 `curl --noproxy '*' http://192.168.42.129:8080/`，或给浏览器加代理例外。

**三个根因（逐层实测）**

1. `pm dump com.sp970.smsrelay` 显示 `stopped=true notLaunched=true`。
   adb 安装的包从未被启动，Android 4.4 会把 stopped 包排除在
   `BOOT_COMPLETED` 之外，`BootReceiver` 从未触发，`RelayService` / 8080 不存在。
   显式 `am start -n com.sp970.smsrelay/.MainActivity` 后 `stopped=false`、
   日志 `[panel] 面板已启动，端口 8080`。
2. 解除 stopped 后**重启仍不自启**：本机 ROM 的开机 `BOOT_COMPLETED` 不送达
   该静态 receiver（手动 `am broadcast -a android.intent.action.BOOT_COMPLETED
   -n com.sp970.smsrelay/.BootReceiver` 立刻能起服务）。因此改为在
   `post_boot.snippet` 里由 init(root) 延迟 `sleep 15` 后 `am start` +
   `am startservice` 拉起，不依赖 app 层广播。relay-helper / 5555 这条
   init 通道在重启后一直正常。
3. 配置模板有 bug：`helper` 被嵌在 `web` 段里，但 `AppConfig.parse()` 读的是
   **root 层** `root.optJSONObject("helper")`；且 `channels: []` 会让
   `load()` 回退到 `sampleJson()`，把 helper 段整个丢掉。结果维护页的
   root 命令通道一直用代码默认值 `sp970-relay-2026`，与设备
   `/system/etc/relay-helper/token` 的 `jz02-relay-2026` 不匹配。
   已修正模板：helper 顶层、channels 放一个 disabled 占位。

**修复后的部署流程**（`deploy-jz02.py` 现在 7 步，全程幂等）

```
python tools/jz02/deploy-jz02.py                 # 含装 APK / 预置配置 / 解除 stopped
python tools/jz02/deploy-jz02.py --verify-only   # 含 8080 与面板 API 校验
python tools/jz02/deploy-jz02.py --verify-only --reboot   # 验证开机自启
```

`deploy_panel()` 会在 APK 缺失时自动 `adb install -r dist/...apk`（刷 v6 整包后
`/data/app` 被清空时也适用）；配置已含 JZ02 token 时**不覆盖**用户在面板上的
改动，否则先备份 `.pre-jz02` 再推送。`verify_panel()` 用
`adb forward tcp:18080 tcp:8080` + Basic auth 从宿主机验证
`/api/status`、`/api/maint/helper`、`/api/health`，不依赖设备网络可达性。

**实测结果**

| 检查 | 结果 |
|---|---|
| `/`（无认证） | `401` + `WWW-Authenticate: Basic realm="msm8916-box"`，浏览器弹登录框 |
| `/`（admin:admin） | `200` |
| `/api/status` | `device=jz02`、`panel.running=true`、port 8080 |
| `/api/maint/helper` | `uid=0(root)`、`alive=true`、`token=jz02-relay-2026` |
| `/api/health` | `healthy=true`、`issues=[]`（panel8080 / helper / smsrelay / ril / simReady / adbd5555 全 true） |
| 开机自启 | 重启后 post_boot 拉起，8080 自动 LISTEN |

默认凭据 `admin / admin`（`config/smsrelay.conf.jz02.example` 的 `web` 段）。
面板只绑 `0.0.0.0:8080`，厂商面板占用的 80 在 v6 已清空，不冲突。


<!-- jz02-panel-fix-addendum-2026-11-07 -->

### 补充：root 看门狗、校验口径与重启瞬态

**root 层看门狗**：`tools/relay-helper/bin/panel-watchdog.sh` 由 crond 每 5 分钟
（`0,5,10,... * * * *`）以 root 调用，只在 `netstat` 没有 `:8080 ` 时执行
`am start` + `am startservice`。覆盖 JZ02 378MB 内存下 LMK 回收 app 进程的情况，
日志在 `/data/local/tmp/relay-helper/panel-watchdog.log`。开机自启仍由
post_boot 片段（延迟 15s）负责，两者互不冲突。

**`/api/health` 只作 INFO**：该接口会经 helper 跑一段 shell 体检，在 378MB
内存设备上偶发 20s 超时；`memOk` 判据是裸 `MemFree > 20000`（不扣可回收
cache，本机常年 false），开机窗口内 SIM 还会短暂 `ABSENT`。这些都不代表
面板不可用。`deploy-jz02.py` 的硬断言是：8080 监听、包非 stopped、配置含
JZ02 token、`/api/status` running、helper root 通道 uid=0 且 token 匹配。
`--reboot` 分支的校验结果现在也会正确反映到退出码。

**重启后的 USB RNDIS 重新枚举瞬态（重要）**：设备刚重启的 1~2 分钟内 USB
gadget 会重新枚举，`rndis0` 可能短暂没有 `192.168.42.129/24`，
`sys.usb.config` 从 `rndis,none,adb` 收敛到 `rndis,adb`，期间 USB adb 也会
短暂掉线重连。此时主机侧 `curl` 超时、浏览器打不开，设备侧
`wget http://192.168.42.129:8080/` 报 `Network unreachable`，但
`wget http://127.0.0.1:8080/` 一直正常返回 401。**不是面板故障**，等 USB
重新枚举完成即可。排查顺序：设备 `netstat -ltn | grep 8080` → 设备
`127.0.0.1:8080` → 设备 `ip addr | grep rndis0` → 主机 `curl --noproxy '*'`。

**Windows 侧**：若系统配了代理，`Invoke-WebRequest` / 浏览器访问
`192.168.42.129:8080` 会看到 `502 Bad Gateway`（代理返回，不是设备）。
给该地址加代理例外，或用 `curl --noproxy '*' -u admin:admin ...` 验证。

**最终实测（2026-11-07）**：`deploy-jz02.py --verify-only --reboot` 重启后
校验「全绿」exit=0；主机直连 `http://192.168.42.129:8080/` 连续 3 次
`HTTP 200`（约 10ms，首页 `<title>MSM8916 Box 控制台</title>`），
无认证返回 `401 + WWW-Authenticate: Basic`。SIM 开机约 1 分钟后从 ABSENT
恢复到 `READY / 中国电信`。

**分工**：`BAK-analysis/work/live/verify-hardened.py` 管刷包落地（新增面板
状态 INFO 项）；`tools/jz02/deploy-jz02.py --verify-only` 管面板完整可用性。
