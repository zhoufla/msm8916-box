# JZ02 适配层（msm8916-box 本地 fork）

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
>
> **⚠️ 2026-11-09 更新**：当前真机已改为**无 adb 公钥认证**的 v5.1 boot
> （`ro.adb.secure=0`，boot 分区 md5 `0a5eae6e58fe6cfd7150692af436f06f`）。
> 上文（含安装节「只有这台电脑能连」）及「v5 修复与真机验证结果」中
> 关于 `ro.adb.secure=1`、adb 公钥、`c741af6f…2925` 的描述是
> **v5 当时的历史状态**，现已不适用；详见文末「附：v5.1 —— 去掉 adb 公钥认证」。

本目录是 `msm8916-box` 针对 **JZ02（UFI / richal / juzhen，msm8916，Android 4.4.4 `KTU84P test-keys`）** 的适配层。
原项目 `com.sp970.smsrelay` 的 Java 代码、Web 面板、短信转发逻辑**全部复用不改**（包名保持不变，避免大规模重构）；
差异集中在「设备侧部署」这一层。

> 这是**本地 fork**：路径 `/home/zhoufl/github/msm8916-box-jz02`，分支 `jz02`，基线提交 `snapshot: upstream working tree`。
> 原目录 `/home/zhoufl/github/msm8916-box` 及其未提交改动**完全没动**。

---

## 一、SP970 与 JZ02 的差异（决定了不能直接跑 `deploy-helper.py`）

| 项 | SP970 | JZ02 | 适配动作 |
|---|---|---|---|
| ROM | webkey / goso / pingbao 套件 | richal / UFI / juzhen，原厂安卓 GPT 27 分区 | 精简清单不通用 |
| root 通道 | web 控制台写回 adbkey + post_boot 注入 | **v5 全量包换入 root 版 adbd → adb shell 直接 root** | 部署脚本直接 `adb remount` |
| busybox | `/system/xbin/busybox` 已有 | **原厂完全没有**；强化档还把 `xbin/*` 清空 | 本目录自带 `busybox`（armv7，取自 SP970） |
| `init.qcom.post_boot.sh` | 767 行，末尾两个 40×15s / 20×15s 长循环，锚点 `setprop service.adb.tcp.port 5555` | **728 行顺序执行、无长循环、无该锚点** | 片段插在 **shebang 之后**；`deploy-helper.py` 加锚点回退 |
| 网络 adb | 开机自启 `192.168.42.129:5555` | **默认关闭**，全镜像无 `service.adb.tcp.port` | 片段里 `setprop` + `ctl.restart adbd`（可关） |
| Web 管理面板 | 应用自带 8080 | 厂商 `ufilauncherzx` 用 `192.168.100.1/api`（80） | 8080 大概率空闲，**实机 `netstat -ltn` 确认** |
| 短信链 | Mms + TelephonyProvider | 无 Mms，但 `TelephonyProvider.apk` / `TeleService.apk` / `mms-common.jar` 在 | `content://sms` 可用，Java 不改 |
| 四核修复 | `tools/hwfix/quadcore` 专用 | **禁止使用**（CPU 调频表不同） | 文档标红，脚本不引用 |
| recovery | 正常验签 | **v3 已去掉 `/res/keys`**，不能 sideload 任何签名 OTA | 刷机一律 MiKo / 分区级 |
| SIM 注网 | 正常 | 标准档实机曾出现 `SIM READY` 但 `OUT_OF_SERVICE` | 待 A/B 定位，与精简无关 |

### 绝对不要做的事

1. **不要**把 `tools/relay-helper/init.qcom.post_boot.sh`（43KB，SP970 完整脚本）推到 JZ02 —— 里面是 SP970 的四核热补丁、RIDL 启动和长循环，会冲掉 JZ02 自己的 728 行调频逻辑。
2. **不要**跑 `tools/hwfix/quadcore/apply.sh`。
3. **不要**在设备上点「切换运营商/规格」—— 强化档已删空 4 套模板，`switch_spec.sh` 带参数会走 `wipe data`。
4. **不要**指望 `adb sideload` 刷 OTA（recovery 公钥已清空）。

---

## 二、文件清单

| 文件 | 用途 |
|---|---|
| `README.md` | 本文 |
| `jz02.env` | 设备参数（serial / adb server / token / 端口） |
| `deploy-jz02.py` | JZ02 专用部署脚本；`--dry-run` 可完全离线验证注入逻辑 |
| `post_boot.snippet` | JZ02 版开机注入片段（shebang 后插入） |
| `busybox` | armv7 ELF，1,152,216 B，396 applets，取自 SP970 `/system/xbin/busybox` |
| `token` | JZ02 专用 helper token（与 SP970 区分） |
| `verify-jz02.sh` | 设备侧自检：root / helper / 端口 / 精简落地 / 注网状态 |
| `flash-and-root.md` | MiKo 刷 v3 全量包、root 语义、回滚 |
| `snapshots/` | 部署时保存的设备侧 `init.qcom.post_boot.sh` 快照 |

---

## 三、完整落地顺序

### 1. 刷入 v5 全量包（一次性完成「强化精简 + 永久 root adb + 解锁 aboot」）

原厂 aboot 的 fastboot 只实现 `getvar / download / reboot`，**`flash` / `erase` / `boot` / 甚至 `reboot` 全部返回 `unknown command`**，
所以 fastboot 直刷分区不可行。走 MiKo：

```
F:\wifi\jz02\BAK-modified\ja02-fullbak-hardened-rooted-aboot-v5.bin
md5 2a6e9cfcc093f1691e57918e9fda0c09
```

详见 `flash-and-root.md`。刷完开机：

```
adb -L tcp:5039 -s 0 shell id     -> uid=0(root)
adb -L tcp:5039 -s 0 remount      -> remount succeeded
```

> 本机 JZ02 走 **5039** 端口的 adb server，SP970 走默认 **5037**，两者隔离、不要 kill 对方进程。

### 2. 安装 APK

```
adb -L tcp:5039 -s 0 install -r dist/com.sp970.smsrelay-1.7.0.apk
adb -L tcp:5039 -s 0 push config/smsrelay.conf.jz02.example /sdcard/smsrelay.conf
```

`ro.adb.secure=1` 保持开启，`/data/misc/adb/adb_keys` 是本机的 `Zzhou@DESKTOP-0L7L114` 公钥 —— 只有这台电脑能连，且连上就是 root。

### 3. 离线先验证注入逻辑（设备没连也能跑）

```
python tools/jz02/deploy-jz02.py --dry-run
```

会拿离线解包的 JZ02 `init.qcom.post_boot.sh` 走一遍幂等注入，输出插入行号、片段长度、幂等复跑结果，并写快照到 `snapshots/`。

### 4. 部署 relay-helper

```
python tools/jz02/deploy-jz02.py
python tools/jz02/deploy-jz02.py --verify-only
```

脚本会：检查 root → 推 busybox → 推 helper 文件（用 JZ02 token）→ 补 `/system/etc/passwd` → 备份并注入开机脚本 → 立即拉起 → 校验。

### 5. 自检 + 面板连通性

```
adb -L tcp:5039 -s 0 push tools/jz02/verify-jz02.sh /data/local/tmp/
adb -L tcp:5039 -s 0 shell sh /data/local/tmp/verify-jz02.sh
```

`smoke-test.sh` / `panel-e2e.sh` 已支持 `RELAY_BASE` 环境变量覆盖默认地址：

```
RELAY_BASE=192.168.42.129:8080 bash tools/smoke-test.sh
```

---

## 四、待实机验证（本轮设备停在 fastboot，未实测）

- [ ] MiKo 刷 v3 包后 `adb shell id` = `uid=0(root)`、`adb remount` 成功
- [ ] `netstat -ltn` 确认 8080 未被厂商面板占用
- [ ] `content://sms` 在无 Mms 的 JZ02 上读写正常
- [ ] 网络 adb 开机自启是否生效（`setprop ctl.restart adbd` 在 4.4 上的行为）
- [ ] SIM 注网：`SIM READY` 但 `OUT_OF_SERVICE` 的原包/修改包 A/B 定位
- [ ] `busybox httpd` 的 8899 只听回环、错误 token 被拒
- [ ] 重启后 `relay-helper` 自动拉起

---

## 五、回滚

| 想回退什么 | 做法 |
|---|---|
| 只去掉开机注入 | `adb shell cp /system/etc/init.qcom.post_boot.sh.pre-helper /system/etc/init.qcom.post_boot.sh` |
| 去掉 helper | `adb shell rm -rf /system/etc/relay-helper /data/local/tmp/relay-helper` |
| 去掉 root adb | MiKo 单独刷回原 `boot` 分区，或刷 `ja02-fullbak-hardened.bin`（不含 root） |
| 全部回到原厂 | MiKo 刷 `F:\wifi\jz02\BAK\ja02-fullbak.bin`（md5 `2fa5ee48073018dd7afa0a9e546f242c`） |

> 包内 NV / `modemst1/2` / `fsg` 与 `/data/property/persist.sys.iccid` 是本机标定，**只能刷回这台设备**。

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

**root 层看门狗**：复用主线 `tools/relay-helper/bin/watchdog-8080.sh`，由 crond 每 2 分钟
（`*/2 * * * *`）以 root 调用，先 HTTP 探活 `/api/ping`，分级处置（广播 --> force-stop+拉起
-> reboot），日志由脚本自写 `/sdcard/Download/smsrelay-watchdog.log`，并新增每 2 分钟
`logsnap.sh` 内核/日志快照（JZ02 无 pstore，断电取证用）。**依赖 JZ02 APK 升到 v1.8.x**
（提供 `/api/ping` + `WatchdogReceiver` + `smsrelay-port`），否则探活恒失败会触发 reboot。
开机自启仍由 post_boot 片段（延迟 15s）负责，两者互不冲突。

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


## 外置 SIM 卡（3 路硬件 mux）

### 为什么需要

JZ02 有一个 3 选 1 的 SIM 硬件开关，内核 `leds-gpio` 导出
`sim1/sim2/sim3_switch_gpio` + `sim_hotdet_gpio`（`/sys/class/leds/*/brightness`）。
内核默认把 `sim1` 拉高 → 内置 eSIM1（`898611...` / 中国电信 46011），**插了实体卡
也读不到**。厂商在 `framework/services.jar` 的 `LightsService.switchSimSlot(1/2/3)`
里定义 1=eSim1、2=eSim2、3=外置实体卡，但只有厂商 Web 面板会发
`android.led.sim.switch` 广播触发它；精简删掉面板后，`sp_sim_slot` 三个命名空间
全为 `null`，开机也没有主动切卡流程。

### LED 定位

同一批 `leds-gpio` 还导出 `red`（power）/`green`（wlan）/`blue`（wan），没有
SP970 的 `torch-light`。面板「LED 定位」在 JZ02 上控制这三颗灯：
先保存 brightness + trigger 并摘 trigger，动作结束后还原（`on` 15s / `off` 5s /
`blink` 8 下），不会永久改掉电源灯和 WLAN 灯。真机 `/api/maint/led` 三种动作
返回 `SENT`，状态均还原。

（SP970 那边的 torch-light 是另一套硬件：定位灯=电源指示灯常亮灭不掉，只能控制
"闪"，详见主 README「LED 定位（双机型）」。）


### 不要用广播热切

```sh
am broadcast -a android.led.sim.switch --ei slot 3   # 实测会挂死整机
```

`switchSimSlot()` 在 system_server 线程里同步 sleep 约 4.5 秒做电平时序，之后
handler 链还会 `toggleRadioOnOff` ×2、延时 8s、恢复 APN。RIL 重连期间整机挂起，
只能断电重插恢复。

### 部署方式（冷启动固化，绕过 framework）

`deploy-jz02.py` 把 `tools/jz02/install-recovery.sh` 推到
`/system/etc/install-recovery.sh`（0755 root）。`init.rc` 里已有
`service flash_recovery /system/etc/install-recovery.sh`（`class main`、`oneshot`、
root、无 `disabled`），在 `on boot` 的 `class_start main` 阶段执行，远早于
rild / modem / framework；精简后该文件本来不存在，服务是空跑。脚本只写 4 个
sysfs 值，让 modem 上电就接在选定的通道上，无 sleep、无 radio 操作。

**默认不写任何 GPIO**——部署脚本本身不改变正在工作的设备的 SIM 通道。需要外置卡时
显式启用：

```sh
setprop persist.jz02.sim.slot 3
reboot
```

| `persist.jz02.sim.slot` | 通道 |
|---|---|
| 未设置 / `0` | 不写（内核默认 eSIM1，安全默认） |
| `1` | 内置 eSIM1 |
| `2` | 内置 eSIM2 |
| `3` | 外置实体 SIM 卡座 |

属性存于 `/data/property`，重启保留。回滚：`setprop persist.jz02.sim.slot 0;
reboot`，或直接删除 `/system/etc/install-recovery.sh`。

### 真机实测（2026-11-07）

冷启动后 `init.svc.flash_recovery=stopped`（钩子确实执行）、
`sim1=0 sim2=0 sim3=1 hotdet=0`；`gsm.sim.operator.alpha` 从 `中国电信 46011`
变为 **`CMCC 46000`**、`gsm.network.type=LTE`；APN `China Mobile / 46000 / cmnet`
本来就存在，`rmnet0` 成功拿到 `10.108.179.11/29` + 移动 DNS；面板 8080
`HTTP 200`、helper 保持 root。

> ⚠ **已知风险（未完全定位）**：**第一次**切到外置卡时，开机约 3 分钟后整机
> 挂起（Windows 侧 USB Composite / ADB Interface / RNDIS 仍 OK，Android 侧
> adb / ping / 8080 全无响应，需断电重插）。第二次冷启动后不再复现：连续稳定
> 16 分钟以上，`mobile_data=1`、`rmnet0` 持有 `10.108.179.11/29`、默认路由
> `via 10.108.179.12`、公网 `ping 223.5.5.5` 2/2 通（101–187ms）。
> 注意「禁用数据后稳定」的推断已被推翻——数据后来自动恢复且设备仍稳定，
> 所以挂起**与数据开关无关**，更像是首次切卡时 APN 迁移 / modem 首次注册 /
> 框架开机状态机的一次性事件。若再次出现，断电重插即可；
> `persist.jz02.sim.slot=3` 存于 `/data/property`，重启仍选外置卡。


## v7：运行态精简（内存 + 负载，2026-11-07）

> 目标：释放内存、降低 load。**不动 boot 分区、不删任何 APK/包**，全部是
> 「二进制改名 + crontab 清理」级别的可逆操作，配合基带挂起前科做了逐项健康闸门
> （每步之后核对 `subsys2/modem` ONLINE 且 `crash_count` 不涨、`com.android.phone`
> / smsrelay / 8080 / 8899 存活）。

### 做了什么

1. **二进制改名（原样备份在 `/system/etc/jz02-slim-backup/`，`adb remount` 后操作）**：

   | /system/bin 下改名为 `.disabled` | 服务 | 处置理由 |
   |---|---|---|
   | `syssn` | init 反复拉起（实测崩溃循环，每秒级 fork+退出+log） | 纯浪费 |
   | `loc_launcher` `xtwifi-inet-agent` `xtwifi-client` `lowi-server` `location-mq` | `init.svc.loc_launcher` + 5 个 gps 进程 | 强化档已断其外联，进程仍白跑 |
   | `mm-qcamera-daemon`（服务 `qcamerasvr`） | 相机 HAL daemon | 无摄像头场景 |
   | `audiod` | 音频 HAL daemon | 无音频场景 |

   `reboot` 后验证：上述服务/进程不再出现、`init.svc` 直接置空（exec 失败即放弃，
   **无重试火焰**）、`mm-pp-daemon`/`surfaceflinger` 原样保留、开机流程完整。

2. **crontab 新增「后台缓存进程回收」**（`tools/relay-helper/crontab` 同步版本化，
   分钟位取 1,6,11,…（模 5），与每 2 分钟的 watchdog-8080 看门狗偶有同分钟并发，互不干扰）：
   每错开 1,6,11,… 分钟 `am kill` launcher3/systemui/keychain/onetimeinitializer/
   settings/providers.contacts/providers.media/defcontainer。
   注意 **包一个没删**：launcher3 仍是 HOME（v6 已证删 HOME 会卡 bootanim）；
   `am kill` 只杀可回收后台（DIRT 上开机早段 fg 级的会被留在原地，随空闲轮次逐批回收），
   LMK 语义不受影响。

### 明确不碰（真机验证后的结论）

- **display 栈（`surfaceflinger`/SystemUI/`mm-pp-daemon`)不能停**：单独 `stop ppd`
  安全（system_server 稳）；但 `stop surfaceflinger` 后 system_server 会在随后
  的框架重启里**拿不到 display 服务而永久卡死**（AMS 不接受 `am`，只能 reboot）。
  context：后台杀 systemui 触发过一次 system_server 软重启 → 加上 SF 已停就 wedge。
  结论：SF 必须随开机常驻，`mdss_*` D 态内核线程是 load 的地板价。
- **联系人/媒体 provider、`com.android.phone`、SMS/RIL 链**：smsrelay 的
  `content://sms` 广播/发送、补收、保活全部依赖，保留。
- WiFi（hostapd/wpa_supplicant/dnsmasq）、relay-helper、两个内置 busybox：面板功能依赖，保留。

### 实测数据

| 指标 | 精简前（原态稳态） | 精简后 |
|---|---|---|
| MemFree（即时） | 20.6MB | 开机冷启动后 31MB；`am kill` 后 49~99MB（缓存进程杀净时冲到 99.6MB） |
| AnonPages | 86MB | 80MB（开机早期读数，还会随 crond 轮次下降） |
| load（15min） | 5.15 恒定不落 | **2.14**（开机后 ~7min 读数仍在收敛；syssn 崩溃循环与 GPS 5 进程的常驻 fork/线程消失） |
| modem SSR | ONLINE / crash=0 | 全程 ONLINE / crash=0（含一次 system_server 软重启周期、NV 写入与数次重启） |
| 面板/保活 | - | `panel.running=true`，keepalive 全 ping OK |

### 回滚

> 原「设备侧副本」`/system/etc/jz02-slim-backup/` 已按用户要求清出 #system 分区，
> 8 个原版二进制移至仓库 [`tools/jz02/slim-backup/`](slim-backup/)（md5 见下表），
> 必要时可 `adb push` 回 `/system/bin/<原名>`。

```
adb remount
adb push tools/jz02/slim-backup/<f> /system/bin/<f>    # 逐个还原，或：
# for f in syssn loc_launcher xtwifi-inet-agent xtwifi-client lowi-server location-mq mm-qcamera-daemon audiod; do adb push tools/jz02/slim-backup/$f /system/bin/$f; done
# crontab 行直接在面板「维护 → 定时任务」删除，或去掉 tools/relay-helper/crontab 里 am kill 一行再重放 deploy
reboot
```

| 文件 | md5 |
|---|---|
| audiod | `d8175a0e05ae6878ecb49c39f5b235e0` |
| loc_launcher | `299a78663a3f784cac75bc92bad897ab` |
| location-mq | `8794e2cf545a3427bf3c1e6abb7ba836` |
| lowi-server | `5f29e6589abd45ce89df2dd132712146` |
| mm-qcamera-daemon | `21784821d4cbc47f79c0b244ab972d89` |
| syssn | `013fb95e06178f69eb228ad16333343a` |
| xtwifi-client | `8fac3dc1448508765049d28489450a9e` |
| xtwifi-inet-agent | `c15a92f403e9448936aaf63fd95109e1` |

### 备注

- **adb server 不要混用**：这台机器的 JZ02 维护通道统一走 WSL `-L tcp:5039`
  （见第十一节）。v7 当轮改用了 Windows 侧另一个 adb（miko 自带 1.0.32，隐式 5037）
  完成 remount 与改名——后续若要跑 `deploy-jz02.py --verify-only`，先按项目惯例
  kill 掉 5037 的 server 或重开 5039，避免三条 server 抢同一条 USB adbd 传输。
- 开机后 MemFree 回落是因为 framework 每次冷启动都会重建缓存进程（HOME 等），
  由 crond 轮次逐步回收；要每次开机更快回血可把 cron 行挪进 post_boot 片段（未做，
  避免与 deploy-jz02.py 的幂等注入互相干扰）。


<!-- jz02-sms-noservice-nv-2026-11-07 -->

## v7·补充：短信发不出去（NO_SERVICE）的根因是 modem NV `Pref Service`

**症状**：面板发短信回 `失败 · 无服务`（SmsManager `RESULT_ERROR_NO_SERVICE`）；
数据域（rmnet0 / LTE 保活 ping）完全正常。

**证据链（逐步、可复现）**：

1. `dumpsys telephony.registry`：

   ```
   mServiceState=1 0 home CMCC CMCC 46000  Unknown LTE CSS not supported
                        ^ 话音域 REGISTRATION_STATE=OUT_OF_SERVICE / 数据域 0=IN_SERVICE
   ```

2. radio 缓冲：`SmsDispatcher` 在客户端就因话音域非 IN_SERVICE 拒发，
   **RIL 请求根本没发出**（`D/RIL_ImsSms sendText` 后无后续，仅两条老 quirk 日志
   `Invalid Validity Period-1`）。
3. modem 对 `VOICE_REGISTRATION_STATE` 的应答恒为
   `{2, null, null, 0, …}`（SEARCHING，永不出 LAC/CID），且从不主动上报 URC。
4. AP 侧自救全部无效：写 `preferred_network_mode`（11/1/22 各试一次 + 重启）、
   飞行模式循环、`stop/start ril-daemon` 都不影响——每次开机，
   `preferred_network_mode` 还会被 framework 重写回 `ro.telephony.default_network`
   的 **11**。这台设备上制式设置属于「重启会还原」的墙头草。
5. 用 QPST 读 modem NV：**`UMTS System → Pref Service` 出厂为 `PS`**
   （纯数据棒默认，不发起 CS 域附着/CSFB 的 LAU），这正是 4 里所有表象的根源。

**修复**：QPST（诊断口 9091）把 `Pref Service` 改为 **`CS and PS`**
（Write to Phone；radio offline → write → online 或直接断电重插让 Android 恢复
`rndis,adb` 枚举）→ 整机 reboot。

**修复后验证（同机实测）**：

```
mServiceState=0 0 home CMCC CMCC 46000  LTE LTE ...   ← 话音域 IN_SERVICE
VOICE_REGISTRATION_STATE {1, 33d4, 03a6cf43, 14, …}   ← REGISTERED，LAC/CID 就位（rat=LTE）
面板 → 10086「CXLL」真发真收，钉钉转发送达
```

**结论与备忘**：

- **这是 modem NV 侧配置问题，与 ROM/精简无关**；v5/v6/v7 的任何 AP 改动既不是
  因也救不了它（文档早前记录的「SIM READY 但 OUT_OF_SERVICE」同根）。
- modem 全程 `subsys2 ONLINE / crash_count=0`，NV 写 + 断电重插没有引发 SSR。
- QPST offline 写入后若整机 hang、只枚举 Diag 9091（无 RNDIS/ADB）：
  可断电重插恢复 Android USB（9091 只是 modem offline 态的透明示象；9008 才是 EDL）。
- `preferred_network_mode` 已还原为改用前的值（读回 9）；被 framework 开机重写回 11
  属本机原有行为，不是本次引入。
- QPST 里 `Preferred Band`（UMTS 段）只勾了 `W-850`/`IMT 2100`——本次
  LTE+CSFB 下话音直接注册，没暴露频段问题。若后续出现 GSM 回落类话音问题，
  下一个排查点就是频段配置（GSM 900/1800）。


<!-- jz02-mdss-vsync-storm-2026-11-07 -->

## v7.2：mdss vsync 风暴 —— JZ02 比 SP970 高负载高温的根因（实测对拍）

> 背景问句：「差不多硬件，JZ02 内存占用更少，但负载和温度始终比 SP970 高」。
> 用网络 adb（SP970 `192.168.10.196:5555`）做了同口径对拍，结论如下。

### 对拍数据（同为 Android 4.4.4 / msm8916，SP970-B `4GWiFi`）

| 指标 | SP970 | JZ02 |
|---|---|---|
| 服务数（init.svc running） | **57（更多）**：多出 audiod/GPS loc_launcher/qcamerasvr/ims 全家/dpmd/atfwd/ril-daemon1 | 51（v7 已精简） |
| SoC 温区（tsens 0/1/2/4/5） | 46/44/44/42/44 ℃ | **50/47/45/47/47 ℃（高 4~5 ℃）** |
| pm8916_tz | 38.1 ℃ | **44.7 ℃** |
| 20s 采样 busy | 24.7%（4 核合计） | 26.6%（相当） |
| 其中 **内核态（sys）** | 12.3% | **19.5%（偏高）** |
| `mdss_fb0` kthread | utime+stime ≈ **0.8%** 单核 | **10.5% 单核（13 倍）**，恒 D 态 |
| `ksmd` | 内核根本没编（无 /sys/kernel/mm/ksm） | 3.3% 单核（见下） |
| MDSS GIC 中断（GIC 104） | **142,436 个 / 开机 3.4h（≈11.7Hz，正常 vsync）** | **0 个（面板链路自开机即死）** |
| dmesg 显示路径 | 干净（`mdss_fb_release` 正常收尾） | **每秒风暴**（见下） |

### 根因：DSI panel 无响应 + framework 按照样连播帧

```
<3> __mdss_fb_sync_buf_done_callback: mdp-fence: frame timeout
<4> mdss_mdp_video_pollwait: vsync poll timed out! rc=-110 status=0x0 mask=0x8000000
```

JZ02 的 DSI panel 等不到 vsync（无实体屏/panel 死路），但 SF 持有 fb0 且
MDP 按 video mode 连续合成，每帧 vsync 轮询整段超时 → `mdss_fb0` 内核线程
忙等（10.5% 单核纯 sys 时间）+ 恒 D 态拉高 loadavg + SoC 持续发热。
SP970 同款驱动、同 fb0 持有者（SF + display 助手）零报错。

### 修复（已验证）：`echo 4 > /sys/class/graphics/fb0/blank`

- **立即生效**：风暴日志停、`mdss_fb0` stime 30s **零增长**（原每 20s +211 ticks）。
- **无副作用**：framework 不受影响（`boot_completed=1`、`smsrelay`/8080/8899/
  modem ONLINE 全正常）——与 `stop surfaceflinger` 会 wedge 框架是两回事，
  blank 是系统自身的 suspend 路径（DSI 关屏命令 0x28 发送失败的 dmesg 报错无害）。
- **持久化**：`relay-helper/start.sh`（repo 版本化，deploy 重推）在
  `ro.product.device=UFI` 门控下延迟 90s 写一次 `blank=4`；blank 文件不可读
  （cat 为空），无法做幂等比较，每 boot 一次即可（framework 自身不会重新拉亮）。

### KSMD（关闭，已执行 2026-11-07）

- `/init.target.rc`（boot ramdisk，改不了）开启：run=1、sleep 500ms、pages_to_scan=100。
- 实测烧 **3.3% 单核**；收益是去重 **pages_sharing≈2964 页 ≈ 11.6MB 内存**
  （SP970 内核没有这功能）。
- **已执行关闭**（用户拍板）：运行时 `echo 0 > /sys/kernel/mm/ksm/run`
  （ksmd stime 20s 零增长验证）+ 落盘：
  `/system/etc/init.qcom.post_boot.sh` OEM 的
  `echo 1 > .../ksm/deferred_timer`（第 469 行）后追加
  `echo 0 > /sys/kernel/mm/ksm/run  # jz02 v7.2: ksm off`，每次开机在
  init.target.rc 开启之后再关掉，时序成立（post_boot 晚于 on boot 属性段）。
- 恢复：把 post_boot 里那行 `echo 0` 删掉重启，即回 OEM 默认（ksmd 常驻）。

### 「还有什么可以精简」的对拍结论

1. **服务面没有更肥的目标**：SP970（参照机）跑的服务反而比精简后的 JZ02 更多，
   JZ02 服务清单已见底；再省只能进 kernel/battery 级。
2. **负载/温度的主项是上述 vsync 风暴**（已修），其余差异为毫巴级杂音。
3. KSMD 已按用户拍板关闭（见上节）；两个内核级热点（mdss 风暴 + ksm）清零后，
   JZ02 的 sys 态负荷应降到 SP970 以下。

### ⚠ loadavg 在本 ROM 上是失真指标

修复后复查发现：JZ02 恒有 **5 个常驻 D 态内核线程**
（`mdss_dsi_event`、`mdss_fb_splash`、`mdss_fb0`、`kworker/u8:0`、`kworker/u9:0`），
SP970 有 4 个同款——loadavg 被「计数里的 D」结构性垫底到 **4.5~5.5**，
与实际 CPU 无关（`mdss_fb0` 修复后从「D+忙等」变「D+纯睡」，loadavg 数字
但 CPU 0）。因此：

- 不要用 loadavg 跨机对比或判定精简效果；v7 表里的 2.14 属瞬态读数（D 线程
  生命周期窗口），典型稳态读数两台都在 4~5.5。
- 健康度量正确姿势：`/proc/stat` 20s 采样算 busy%、tsens 温区、`mdss_fb0`
  utime+stime 增量、ssr crash_count。
- 修复后 JZ02 实测：`mdss_fb0` 30s 零增长、SoC 50→49℃ 起步回落
  （PMIC 44.7→43.2℃），温度随时间继续收敛。

## v8：系统收件箱镜像 + ADB 面板真机验证（2026-11-07，app 1.8.6 / 10806）

### 背景：JZ02 的「系统收件箱」为什么永远是空的

JZ02 精简后系统里**没有任何短信应用**（连默认短信 App 都没有），Android 4.4 的
短信 provider `content://sms/inbox` 因此永远真空：收到的短信只进本 app 的转发
链路（记录正常），面板「系统收件箱」「补收」是死页面。

SP970 不受影响——它的 Mms 是默认短信 App，收到短信会照常写 provider。

### 方案：把收到的事件镜像写进 provider（app 1.8.6 `InboxMirror.java`）

配置项 `mirrorInbox: auto|on|off`（默认 `auto`）：

- `auto`：**只在设备没有默认短信 App 时启用**（SP970 上自动等于 off，绝不双写）；
- `on`/`off`：强制开/关（JZ02 交付配置用 `on`）。

写路径分两级：

1. **app 进程直写**：未来若有默认短信 App 授权给本 app 时零开销；
   4.4 上非默认短信 App 写收件箱吃 `SecurityException`（预期），转 2；
2. **root 代理**：经 relay-helper 跑
   `CLASSPATH=<apk> app_process /system/bin com.sp970.smsrelay.InboxMirror insert|batch …`
   —— 走 `ActivityThread.systemMain()` 起系统 context（**必须先 `Looper.prepare()`**），
   再 `AMS.getContentProviderExternal("sms")` 拿 IContentProvider 直调
   `insert(callingPkg, uri, cv)`，uid=0 绕开 4.4 写权限。直接
   `ContentResolver.insert` 会报 "Unable to find app for caller"（调用方没有注册的
   ApplicationThread）。

写之前按「号码+内容+90s 窗口」查 provider 判重，与 Db 判重口径一致——系统收件箱
页面的「已转发」标记和补收业务因此都成立。

### 这台 ROM 上的三个关键坑（真机验证）

1. **`content` CLI 的 date 显示按 32 位截断**——但 DB 里存的是正确的 Long。
   不要相信 `content query` 显示的 date；写日期必须走 provider insert
   （Long 原样入库），校真实值要直连 `mmssms.db` 读（probe 的 `sql` 模式）。
2. **`content delete` 在这台 ROM 上是空操作**（返回提示但行不删）。删 provider 行
   只能 root 直连 SQLite：`SQLiteDatabase.openDatabase(
   /data/data/com.android.providers.telephony/databases/mmssms.db, OPEN_READWRITE)`
   + `DELETE FROM sms WHERE _id IN (...)`，再补一句清孤儿
   `DELETE FROM threads WHERE _id NOT IN (SELECT DISTINCT thread_id FROM sms)`。
3. **KitKat 的 `Looper.prepare()` 重复调用抛 `RuntimeException`**
   （"Only one Looper may be created per thread"），**不是** `IllegalStateException`。
   只 catch 后者 → batch 模式第 1 行成功、其余行全部异常被吞（表面上"OK 1"），
   一批回填只进 1 条。三处修法：catch `RuntimeException`；单行失败现在
   `Log.i("SmsRelay-mirror", …)` 输出到 logcat（不污染 stdout 的 `OK n` 协议）。

### 竞态修复：镜像操作全部串行化

最初 `storeAsync`（收信即时镜像）与服务启动回填各起各线程，都会先查provider
再写——并发窗口里查不到对方 → **同一条写两遍**（真机复现过）。v1.8.6 起
收件箱镜像的全部操作（store / 回填 / root 代理调用）走同一个
`newSingleThreadExecutor`（daemon）：先写者胜，后来者 providerHas 必然命中，
且 root 的 app_process 调用天然不并发。

### 真机验证（JZ02, 1.8.6）

- TEST 广播注入 → root 代理镜像成功，ts/iso 正确、面板带 `relayed:true` ✓；
- 删掉 provider 内 2 条有 ev 记录的行 → 触发服务回填，batch 一次补写，判重窗口工作 ✓；
- 重装后再次全链路：回填「检查 6 条，补写 0 条」，收件箱零重复 ✓；
- 面板 `/api/sysinbox` `total` 与消息列表干净，`mirror:true` 标志正常 ✓。

### ADB 面板真机验证 + keysCount 修复

`GET /api/maint/adb`（Basic admin/admin）在 JZ02 实测有效：
`mode=tcp、tcpPort=5555、adbd=running、usbState=rndis,adb、connections=[ESTABLISHED]、staleCount=0`。
修复一个原有 bug：keysCount 用 `wc -l` 对**无结尾换行**的 authorized_keys 文件
恒报 0，改为 `busybox awk 'END{print NR}'`，实测 1 ✓。

### sqlite3 缺失 —— APN 读写已改走 content provider（2026-11-08）

JZ02 精简掉了 `/system/xbin/sqlite3`（也没有 `/system/bin/sqlite3`），上游
`Radio.java` 原先用外部 sqlite3 CLI 读 telephony 库，导致面板 APN 恒显示
「共 0 条」。现改为 `content query` 读、`content insert/update/delete` 写，
与首选 APN 同一条 provider 通道；`findApnId` / `countApn` 也一并去掉 CLI 依赖。
真机实测：China Mobile / cmnet（_id=2685）可正常列出，新增后能回读 id 并出现在列表。

### 验证工具

`tools/jz02/provider-write-probe/`（`test.sh` 编译打包 → push
`/data/local/tmp/tiny.jar` → `app_process` 跑 `t.Tiny`）：

- `insert <address> <body> <ts>`：provider 路径原型（本功能可行性验证用）；
- `batchraw <peer1> <body1> <ts1> …`：逐行带回显的 batch 复现（找坑 3 用的）；
- `delsql <ids>` / `sql`：删行 / 直读 mmssms.db 两个运维模式；
- `batch <b64json>`：与 InboxMirror 同构，验证 JSON 协议。

probe 原型经验已固化进 `InboxMirror.java`，probe 仅作后续排障工具保留。

### 版本

- 1.8.6（versionCode 10806），`dist/com.sp970.smsrelay-1.8.6.apk`，
  sha256=22dcf4e89d0cdf7b…（build.sh 输出为准），已 `-r` 重装至真机并全链路验证。

## v9：最小默认短信 App 化（2026-11-08，app 1.8.7 / 10807）

### 决策

不再目标「装一个完整短信 App」（SP970 的 Mms 太重、进 priv-app、内存回归），
而是把**本 app 变成 4.4 意义上的默认短信 App**：收到短信由 framework 点对点
投递给本 app，收件箱写权限（OP_WRITE_SMS）由框架自动授予——root 代理从
「收件箱镜像主路径」降级为 fallback。SP970 也用同一机制停用原装 Mms。

### 组件要求（4.4.4 `SmsApplication.isComplete` 校验集，安卓 manifest 四件套）

| 组件 | intent | 权限属性（调用方需持有） |
|---|---|---|
| `SmsDeliverReceiver` | `android.provider.Telephony.SMS_DELIVER` | `BROADCAST_SMS` |
| `WapPushReceiver` | `android.provider.Telephony.WAP_PUSH_DELIVER` + mime `application/vnd.wap.mms-message` | `BROADCAST_WAP_PUSH` |
| `RespondViaMessageService` | `android.intent.action.RESPOND_VIA_MESSAGE` + scheme `smsto` | `SEND_RESPOND_VIA_MESSAGE` |
| `SendToActivity` | `ACTION_SENDTO` + scheme `sms/smsto/mms/mmsto` | （无） |

全部是 no-op/薄壳（本 app 不做 MMS、无会话 UI）；权限写在本组件上表示
调用方（phone 进程/拨号 UI）需持有，本 app 无需申请。

### 双播去重（KitKat 入站两腿，实测源码确认）

4.4.4 `InboundSmsHandler` 只发一次 SMS_DELIVER：有默认 App 时点对点投给默认 App
的 SMS_DELIVER 接收器；广播完成后 framework 的 resultReceiver 再把同一条 PDU 以
`SMS_RECEIVED_ACTION` 广播给所有接收方（legacy 腿，无默认 App 时 JZ02 今天靠这腿工作）。
默认化后本 app 两条腿都会收到 → 事件落库靠 `Db.insertEvent` 的
peer+body+ts±90s 判重天然吸收（同 PDU 两腿 ts 完全相同），投递/镜像均不双发。
`SmsReceiver.handle(+leg=deliver|legacy)` 两腿共用同一处理入口，日志带腿别。

### 默认化操作与 OP 自愈（这台 ROM 没有 appops CLI）

`tools/jz02/set-default-sms.ps1`（`-CheckOnly` / `-Unset` / `-MmsDisable` /
`-MmsEnable`，SP970 记得 `-AdbServer tcp:5037`）：

1. `settings put secure sms_default_application com.sp970.smsrelay`（持久化）；
2. OP_WRITE_SMS 放行：本 ROM 把 `appops` CLI 也精简了，**依赖框架自愈**——
   framework 每收到一条真实 SMS 都会调
   `SmsApplication.getDefaultSmsApplication(ctx, updateIfNeeded=true)`：
   校验四组件 → 放行本包 OP_WRITE_SMS → 把旧默认 App 的 OP 降级 ignored →
   重建 SENDTO preferred activity。所以设置后**第一条真实短信到达即完成接管**；
3. 自愈与设置均持久化，重启不回退。

已验证（真机，10086 CXLL 实测回路）：
- `settings get secure sms_default_application` = `com.sp970.smsrelay` ✓；
- probe `checkdefault` 四组件 `COMPLETE` ✓（`tools/jz02/provider-write-probe` 的
  tiny.jar，纯 PM 查询——root app_process 代理没注册 ApplicationThread，碰
  Settings/`SmsApplication` 反射会吃 `Unable to find app for caller`，这是已知边界）；
- **真实短信验证**：给 10086 发 `CXLL`（TEST 广播 `--es sendto 10086 --es body CXLL`），
  回复数秒到达，framework 日志实证：
  · `recv 10086 … leg=deliver` —— SMS_DELIVER 点对点投给了我们的接收器（默认化生效）；
  · 随后 `recv 10086 … leg=legacy` —— legacy 腿同 PDU 再次入库，被 Db 判重吸收，
    钉钉只投一次、收件箱只写一行 ✓；
  · `appops.xml` 出现 `<op n="15" />`（OP_WRITE_SMS 由框架自愈放行）✓。

### 第四个坑：WRITE_SMS 必须在 manifest 声明（装机时就答复）

真实短信时镜像第一次仍走 root 代理，异常带出真相：
`Permission Denial: writing … SmsProvider … requires android.permission.WRITE_SMS`
——provider 写放行是**两层**：manifest 声明 WRITE_SMS（4.4 上 protectionLevel=dangerous，
装机即授）**+** AppOps OP_WRITE_SMS（默认 App/自愈给）。只靠 OP 一层，
没声明 manifest 权限时装机权限检查直接拒绝。修复=`<uses-permission
android:name="android.permission.WRITE_SMS"/>`，之后 TEST 广播实证
`收件箱镜像成功（app 直写）`——root 代理降级为 fallback（直写先试，SecurityException
才转 root），entry 日志会带出受限的异常消息文本便于观测。

### SP970 停用原装短信 App

SP970 仍可用（默认 5037 adb server）：

```
./set-default-sms.ps1 -AdbServer tcp:5037 -Serial <SP970_usb_serial> -MmsDisable
```

`pm disable-user com.android.mms` 后 Mms 不再写收件箱/发通知，收件箱统一由本 app
负责（reflect `setDefaultApplication` 会把 Mms 的 OP_WRITE_SMS 自动降为 ignored，
脚本里 extra 也补了）。回退：`./set-default-sms.ps1 -AdbServer tcp:5037 … -Unset`
再 `-MmsEnable`。**注意**：SP970 的 jz02 fork 配置示例 `config/smsrelay.conf.jz02.example`
不适用 SP970；SP970 上 `mirrorInbox` 保持 `auto`（Mms 停用后 `noDefaultSmsApp`
自动变 true，不需要改配置），改默认后直接走 app 直写。

**SP970 实测记录（2026-11-08，网络 adb `192.168.10.196:5555`，串口别再用
`tcp:5037 -s 0`——那是 USB 侧挂的 JZ02）**：

- 此前「SP970 无 mms 包」的结论是误查 JZ02 所得——SP970 实际有
  `com.android.mms`，且曾是 `sms_default_application`，收件箱 17 行真机短信由它写；
  SP970 实测清单（都确认过）：`pm path com.sp970.smsrelay`、
  `settings get secure sms_default_application`、`pm list packages | grep mms`。
- 装 1.8.7 `-r` → probe `checkdefault` 四组件 `COMPLETE` ✓ →
  `set-default-sms.ps1 -AdbServer tcp:5037 -Serial 192.168.10.196:5555 -MmsDisable`：
  settings ✓、Mms `disabled-user` ✓（appops CLI 两台都缺，容错跳过）。
- **10086 CXLL 真实回路**（TEST 广播 `--es sendto 10086 --es body CXLL`）：
  `leg=deliver` 点对点 ✓ → `[mirror] 收件箱镜像成功（app 直写）`（**首试成功**，
  连 JZ02 的首轮 OP 竞态都没有——provider 写依据 manifest 声明的 `WRITE_SMS`
  即放行，OP 只是框架簿记）→ `leg=legacy` 判重吸收 ✓（钉钉 1 次、收件箱 1 行 `_id=23`）。
  面板（SP970 也是 `admin:admin`）`/api/sysinbox` `total=18` 无重复 ✓。
- appops.xml 延迟刷写：直写已成功时本包下可能暂无 `<op n="15"/>`，属正常簿记延迟，
  不影响功能。SP970 `/data/local/tmp` 另有大量历史部署备份件
  （smsrelay-appdata-*.tar.gz、stage181-183、rp3-7.sh 等）未清——属用户既有产物，
  待确认后另行处理。

### 版本

- 1.8.7（versionCode 10807），`dist/com.sp970.smsrelay-1.8.7.apk`，
  sha256=df2bfca5e0199769…（含 WRITE_SMS manifest 声明与 directInsert 诊断日志），
  已 `-r` 重装至 JZ02 并完成 10086 真实短信回路验证（见上）。


<!-- jz02-framework-mode-denied-nv-band-2026-11-09 -->

## v10：framework 制式层实测否决 + 弱信号短信的 NV 频段落点（2026-11-09）

**痛点**：RSRP −114 ~ −115 / RSRQ −18 ~ −20 / level 1，弱信号下短信收发不稳；
希望设备别只驻留 LTE，能回落到覆盖更好的 2G/3G 保短信，哪怕上不了网。

### 先证伪：改 framework 制式没用

- `ro.telephony.default_network=9` 在 `/system/build.prop:66`；65 行
  `#default network mode set to lte` 只是误导性残留注释。**`9` 是
  LTE/GSM/WCDMA 自动，`11` 才是 LTE_ONLY**。开机由 framework 用它初始化
  `Settings.Global.preferred_network_mode`；无 init.d / post_boot / crontab 写 mode。
- 可逆对照实验：`settings put global preferred_network_mode 3`（GSM/WCDMA，
  不含 LTE），持续约 3 分钟，`gsm.network.type` 恒 `LTE`、
  `mServiceState=0 0 home CMCC 46000 LTE LTE`、CS 域 IN_SERVICE、
  短信队列 failed=0；radio/main 日志**没有任何 `SET_PREFERRED_NETWORK_TYPE`
  下发请求**。实验后已写回 `9` 并验证恢复。
- 与提交 `8785f47`「面板：移除网络制式设置」及本文件 v7 补充（687-690 行）
  的结论一致；且证明 NV 改成 `CS and PS` 之后该结论依然成立。
- **结论**：framework 层既没把制式下发给 modem，改了也不改变注册 RAT。
  不改 build.prop、不重写 framework 默认值、不做开机守护去覆盖 mode。

### 真正的锁：modem NV 频段

- v7 已把 `UMTS System → Pref Service` 从 `PS` 改成 `CS and PS`，CS 域因此在
  LTE 上经 SGs/CSFB 注册（`VOICE_REGISTRATION_STATE {1, …, 14}`），短信才通。
  `RIL_REQUEST_VOICE_RADIO_TECH {14}`、IMS 关闭
  （`persist.radio.calls.on.ims=0`、`IMS_REGISTRATION_STATE` GENERIC_FAILURE），
  所以这台机器没有 VoLTE 短信这条路。
- 但 `Preferred Band`（UMTS 段）**只勾了 `W-850` / `IMT 2100`，GSM 900/1800
  未启用**（本文件 715-717 行当时已预言「GSM 回落类话音问题」）。modem 搜不到
  2G，只能留在 LTE —— 这才是「信号只落在 LTE 上」的原因。

### QPST 操作清单（需诊断口 9091，人工执行）

1. 先 `Read from Phone` / 导出当前 NV 完整快照留底，作为回滚依据。
   本机 NV / `modemst1,2` / `fsg` 是设备标定，只适用这台（见 149 行）。
2. 确认 `UMTS System → Pref Service` = **`CS and PS`**，**不要改回去**；
   改回 `PS` 会重现 `RESULT_ERROR_NO_SERVICE`（见 v7 补充）。
3. 在 band 相关项里启用 **GSM 900 (P-GSM) / GSM 1800 (DCS)**，
   保留现有 `W-850` / `IMT 2100`。项名以 QPST 实际读到的为准，可能分布在
   `Preferred Band` 与独立的 GSM band 项；**先截图留底再改**。
4. 若有独立的 LTE band 项，核对保留移动低频段 **B8(900) / B3(1800)**，
   别只剩 B38/B40/B41 高频（室内穿透差，正是 −115 的来由）。
5. `Write to Phone`：radio offline → write → online；或断电重插让 Android
   恢复 `rndis,adb` 枚举。只枚举 Diag 9091 不是变砖，9008 才是 EDL（711-712 行）。
6. 整机 reboot，等 2 分钟让注册稳定。

### 验证（tools/jz02/rf-check.py，全程只读）

```bash
python3 tools/jz02/rf-check.py snapshot --save rf-before.json   # 改前基线
python3 tools/jz02/rf-check.py snapshot --save rf-after.json    # QPST 写入 + reboot 后
python3 tools/jz02/rf-check.py compare rf-before.json rf-after.json
python3 tools/jz02/rf-check.py watch --count 12 --interval 5    # 观察重选/掉注册
```

`compare` 的判定：

| 结果 | 含义 |
|---|---|
| `voice RAT` 变成非 LTE（GSM/UMTS/…） | **回落生效，目标达成** |
| `voice state` 不是 `IN_SERVICE` | 短信会 `RESULT_ERROR_NO_SERVICE`，立刻回滚 NV |
| `checkup.healthy=True`、`queue.failed` 不增、`sms_default_app` 不变 | 短信链路没被拖坏 |
| `voice RAT` 仍 LTE | NV 未写入成功，或当地移动 2G 已退频 / 该卡未开通 2G 附着；用 `watch` 与 `snapshot` 的 `cells` 看重选 |

### 回滚

QPST 用步骤 1 的快照把 band 项改回只勾 `W-850` / `IMT 2100`，
**保持 `Pref Service=CS and PS`**，reboot，再跑一次 `snapshot` 与改前对比。
若整机只枚举 Diag 9091、无 RNDIS/ADB，断电重插即可。

### 风险与备忘

- 移动 2G 在部分地区已退频重耕；启用后若搜不到 2G，可能增加重选次数、
  反而更不稳。改前务必留 NV 快照。
- 刷含 NV 的 modem 镜像会把 `Pref Service` 打回 `PS`，需按 v7 补充重新写入。
- 本次诊断全程只读（除可逆的 `mode=3` 对照实验，已回滚），未改任何持久化
  配置、未动 `/system`、未动 NV；设备最终 `mode=9`、CS 域 IN_SERVICE、
  checkup 全绿。

## v11：`46002` 是 SIM 归属 PLMN，不是注册网络（2026-11-09）

**起因**：对比两台设备时看到 JZ02 是 `46002`、SP970 是 `46000`，一度据此推断
「两台驻留 PLMN 不同 → SIM 卡网络侧频段策略不同」。但**互换 SIM 卡后 JZ02 仍是
`46002`**，这个推断站不住。实测字段来源后确认是**读错了字段**：此前基于
「PLMN 不同」推出的「SIM 网络侧频段/小区优先级」推断予以撤销。

### 字段来源与实测值

面板 `/api/radio` 的 `simNumeric` 来自 `getprop gsm.sim.operator.numeric`
（`Radio.java:62,102`），是 `SIMRecords` 用 **IMSI 的 MCC+MNC** 写出的 SIM
归属 PLMN（HPLMN）；**注册网络是另一个属性 `gsm.operator.numeric`**。

| | JZ02 | SP970 |
|---|---|---|
| IMSI | `460028023218781` | `460008311848598` |
| `gsm.sim.operator.numeric`（SIM HPLMN） | `46002` | `46000,` |
| `gsm.operator.numeric`（注册网络） | `46000` | `46000` |
| `mServiceState` | `0 0 home CMCC CMCC 46000 LTE LTE` | 同左 |
| 小区广播 PLMN | `mMcc=460 mMnc=0`，PCI 135 / CI 61556033 / TAC 13268 | — |
| `gsm.operator.isroaming` | `false` | `false,false` |

两台**实际都注册在中国移动 46000、都是 home、都不漫游**。`46000 / 46002 /
46007 / 46008` 都是中国移动的 PLMN，互为 ePLMN（等效归属网络），所以 IMSI
为 `46002` 的卡驻留 46000 小区不算漫游、仍显示 `CMCC`。

**结论**：`46002` 与 RSRP 差 23 dB、与弱信号下收不到短信**没有因果关系**，
它不是「换了个运营商/网络」的标志，只是这张卡的号段属性。信号差异仍回到
驻留频段/小区与天线链路（见 v10 的 NV 频段结论）。

### 为什么「互换卡也还是 46002」

`46002` 是卡的固有值，严格换卡并冷启动后必然变成 `46000`。当时不变，是
**属性值没被刷新**，不是卡没换：

- `gsm.sim.operator.numeric` 由 `SIMRecords` 在开机 SIM READY 时一次性写入，
  是全局缓存属性；msm8916 老 qcril 热插拔不保证上报 card-state change /
  REFRESH，framework 就不会重读 IMSI。旁证：JZ02 的 `getSimSerialNumber()`
  只上报 12 位 ICCID 前缀（`898600103124`），SIM 读取链路本就不完整。
- JZ02 的 SIM 走 **GPIO 多路器**：`persist.jz02.sim.slot=3`（外置实体卡座），
  由 `/system/etc/install-recovery.sh` + LightsService 在开机时切；hotdet
  idle low、mux 默认停在内置 eSIM1。按 551-577 行，切卡必须
  `setprop persist.jz02.sim.slot N; reboot`，首次切卡还曾整机挂起需断电重插。
- 面板网络页的 APN 记录（`numeric=46002` 的 `China Mobile / cmnet`）是设备
  数据库里的静态条目，`Radio.java:611` 按 `simNumeric` 选 APN，不重启不会重选，
  也会造成「看起来一直 46002」。

**一锤定音的验证**（换卡后整机 reboot，不要只热插拔）：

```bash
curl -su admin:admin http://192.168.10.174:8080/api/radio | jq '{imsi,simNumeric,iccid,operator,netType}'
curl -su admin:admin -H 'Content-Type: application/json' \
  -d '{"cmd":"echo sim=$(getprop gsm.sim.operator.numeric); echo net=$(getprop gsm.operator.numeric); echo slot=$(getprop persist.jz02.sim.slot)"}' \
  http://192.168.10.174:8080/api/maint/cmd
python3 tools/jz02/rf-check.py snapshot     # 已同时采两类 PLMN，避免再混读
```

| 结果 | 判读 |
|---|---|
| IMSI 变 `46000...` 且 `simNumeric=46000` | 换卡生效，属性随卡走 |
| IMSI 变 `46000...` 但 `simNumeric` 仍 `46002` | framework 缓存未刷新（`SIMRecords` 没重载） |
| IMSI 仍是 `46002...` | 卡没被识别：查 `slot=3`、GPIO mux、卡座接触 |

### 顺带一条对弱信号更直接的证据

一次 `GsmSST.getAllCellInfo()` 有效采样显示：服务小区 **PCI 135**
（RSRP −116 / RSRQ −18），同时存在邻区 **PCI 154**（RSRP −106 / RSRQ −11）——
邻区强 10 dB、RSRQ 好 7 dB，却没有成为服务小区。日志里只有一次有效采样，
需多次确认，但至少说明现场有更好的小区可选。这更像是**测量/重选被 NV 频段
配置限制**，而不是单纯「位置差」，与 v10 的 QPST 改 band 方案一致。
`rf-check.py` 已把 `cells`（服务/邻区 PCI、RSRP、RSRQ）纳入快照与 `compare`，
改 NV 前后可看 PCI 是否变化。

本次全程只读，未改设备配置；仓库改动仅 `tools/jz02/README.md` 与
`tools/jz02/rf-check.py`。

---

## 附：v5.1 —— 去掉 adb 公钥认证（2026-11-09 实机）

<!-- v5.1-noauth-2026-11-09 -->

> 本附录描述 **2026-11-09 之后的当前真机状态**。上文「v5 修复与真机验证结果」
> 里的 `ro.adb.secure=1`、两处公钥、`work/boot_rooted_v5.img` =
> `c741af6f…2925`，以及安装节「只有这台电脑能连」，
> 都是 **v5 当时的历史记录**，现已不适用；原文保留不改写。

### 1. 目标与前提

用户确认「根本用不到」公钥认证，要求 JZ02 与 SP970 一致。两台真机体检
（面板 `/api/maint/cmd` 的 root 通道，全程只读）：

| 项 | JZ02 `192.168.10.174` | SP970 `192.168.10.196` |
|---|---|---|
| `ro.adb.secure` | 1（ramdisk `/default.prop`） | 未定义（adbd 默认不认证） |
| `/system` 挂载 | ro | rw |
| SELinux | Disabled | Enforcing |
| 5555 | `0.0.0.0:5555` / adbd | 同左 |

`ro.*` 属性运行时改不动：无害探针属性首次 `setprop` 成功、再设新值仍读回旧值，
两台一致。所以 `setprop ro.adb.secure 0; stop/start adbd` 无效，
**永久去掉只能改 boot ramdisk 的 `default.prop`**。

### 2. 做法：不刷机、不用 fastboot 的 1 字节改动

1. `dd if=/dev/block/mmcblk0p22 of=… bs=4096` 备份 16MB，md5
   `c741af6f5d1d9612573296dcb8fa2925`，与 v5 记录一致，镜像可信。
2. 布局：`ANDROID!` header，kernel 6,435,776B、ramdisk.gz 536,466B、
   dt 292,864B、`page_size=2048`；ramdisk 区到 dt 之间有 536,576B 空间。
3. 解压 ramdisk.gz → cpio，把 `default.prop` 的 `ro.adb.secure=1` 改成 `0`：
   **16MB 镜像里只有这 1 字节 payload 变化**。gzip 头、header `id`、
   kernel、dt、镜像尾部逐字节不变（原 `id` 与 mkbootimg 算法不符，说明
   bootloader 不校验，所以不动它）；level 9 重压缩得 536,467B，不挤动 dt；
   只更新 header offset 16 的 `ramdisk_size`。新镜像 md5
   `0a5eae6e58fe6cfd7150692af436f06f`。
4. PC 侧临时 HTTP 服务设备连不上（跨网段 Connection refused），改**差分传输**：
   只把 536,467B `newrd.gz` 分 14 块 base64 经 `/api/maint/cmd` 送到
   `/data/local/tmp`，再加 4B size，在设备侧基于备份镜像拼出完整 16MB，
   md5 与本地一致。
5. `dd if=… of=/dev/block/mmcblk0p22 bs=4096; sync`，
   **从分区读回 md5 与新镜像一致**；`reboot`，21 秒后面板恢复。

### 3. 重启后验证

| 项 | 结果 |
|---|---|
| uptime | 4282s → 50s（确认真的重启） |
| `getprop ro.adb.secure` / `/default.prop:10` | `0` / `ro.adb.secure=0` |
| adbd / 5555 | running / `0.0.0.0:5555` |
| boot 分区 md5 | `0a5eae6e58fe6cfd7150692af436f06f` |
| 面板 `/api/maint/adb` | `ok=true, secure=false, staleCount=0` |

开机的 `init.qcom.rc:180 copy /system/etc/adbkey.pub → /data/misc/adb/adb_keys`
仍执行，但 adbd 不再认证，无影响；`/default.prop` 第 5 行注释仍写
`ro.adb.secure=1`（历史注释，未改，不影响功能）。

### 4. ⚠️ 安全影响

两台现在都是 **`0.0.0.0:5555` + `ro.secure=0` + 无认证**：
任何能路由到 10 网段的机器 `adb connect` 即可拿到 root，
8080 面板密码在 adb root 面前不构成防护。建议用 `iptables` 限制 5555 来源，
或改 / 关掉 `service.adb.tcp.port`（SP970 另有
`persist.service.adb.tcp.port=5555`）。SP970 理论上可
`setprop ro.adb.secure 1` 一次性开启认证，但 ro 属性一旦创建就无法运行时
改回、只能重启撤销，不要自动化。

### 5. 回滚与中间产物

按用户决定，本次**已删除全部原 v5 boot 镜像副本**：设备
`/sdcard/Download/boot_backup_adbsecure.img`、本机 `%TEMP%\boot_orig.img`，
以及设备 `boot_noauth.img`、`newrd.gz`、`rsize.bin`、`adbprop-byte`、
临时 HTTP 探针与本地镜像副本。因此没有现成备份能直接 `dd` 回去。

恢复认证 = 把那 1 字节改回去：从当前 boot 分区读出镜像，解压 ramdisk.gz，
cpio 里 `default.prop` 的 `ro.adb.secure=0` → `1`，沿用相同 gzip 头重压缩
（≤536,576B），写回 ramdisk 区（offset 6,438,912），更新 header 的
`ramdisk_size`，`dd` 回 `/dev/block/mmcblk0p22`，读回校验后 reboot。
不必强求 md5 等于 `c741af6f…2925`（gzip 流不同但功能等价）。

仅临时恢复（重启即失效）也可把属性共享内存 `/dev/__properties__` 里
`ro.adb.secure` 的值字节改为 `1` 再 `stop/start adbd`；偏移**必须在当次
开机动态定位，不要硬编码 2604**（该偏移只对本次开机有效）。

残留：SP970 内存里的无害探针属性 `ro.adb.secure.probe=1`
（Android 无 `unsetprop`，重启即消失），JZ02 重启后已消失。
本次**未改动任何仓库代码**，仅增补 `tools/jz02/README.md` 与
`tools/jz02/flash-and-root.md` 两份文档。

---

<!-- jz02-framework-solidify-2026-11-09 -->

## v12：外置卡锁定 + 制式恢复 + 厂商广播摘除（2026-11-09）

三项目标一次落地，**整机 reboot 实机验证通过**，`/api/health` 九项判据全绿。
全程先备份、先建带外恢复通道，可完整回滚。

### ① 单卡「一定」是外置卡：开机前锁死 mux

`tools/jz02/install-recovery.sh` 在写完 GPIO 后追加 `lock_sim_mux`，把 6 个
SIM 切换/热插节点 `chmod 0444`。依据：`/init.rc` 的 `chmod 0777` 在 `on boot`
400-405 行，`class_start main` 在 431 行，本脚本（`service flash_recovery`，
class main / oneshot / root）在后者执行，故 0444 一定覆盖 0777；全机无后续
再改这些节点的脚本。`slot=0`（不写 GPIO）路径也会锁，root 凭
`CAP_DAC_OVERRIDE` 仍可在下次冷启动改通道，`system_server`(uid 1000) 不能。

重启实测：`init.svc.flash_recovery=stopped`、4 个节点 `-r--r--r--`、
`sim1=0 sim2=0 sim3=1 hotdet=0`、`persist.jz02.sim.slot=3`、`sim_state=READY`。

### ② 网络制式「保留且生效」

**根因（精确反汇编 `telephony-common.jar` 的 `PhoneFactory.makeDefaultPhone`）**：

| | 逻辑 |
|---|---|
| SP970（公版） | `Settings$Global.getInt("preferred_network_mode")` → `const/16 10`（`NT_MODE_LTE_CDMA_EVDO_GSM_WCDMA`）→ `putInt` |
| JZ02（厂商） | 同位置多 32 个 code unit：插入 `richal default network mode=` 日志，并把两个分支的写回值都改成 **`const/16 11` = `NT_MODE_LTE_ONLY`** |

这就是「每次开机 `preferred_network_mode` 被重写回 11、只驻留 LTE」的根因。
v10 证伪的是**运行时**改 Settings（RIL 早已在开机时用 11 构造完毕），与本次
结论不冲突：要生效必须改开机时写回/传给 RIL 的那个常量。

**补丁**：两条 `const/16 11` → **9**（`NT_MODE_LTE_GSM_WCDMA`，与
`ro.telephony.default_network=9` 一致，且只含这台移动定制基带支持的
LTE/GSM/WCDMA，不强上 CDMA/EVDO）。厂商日志、分支、RIL 下发能力**全部保留**，
只改 2 字节常量 —— 即「保留且生效、硬件和基带支持的前提下」。

重启实测：`preferred_network_mode=9`、`default_network=9`、`buildprop=9`；
`/api/radio` `mode=9 / modeLabel=GSM/WCDMA/LTE 自动`、`netType=LTE`；
`mServiceState=0 0 home CMCC CMCC 46000 LTE LTE`（话音+数据 IN_SERVICE）。

> **边界（重要）**：radio 日志仍无 `SET_PREFERRED_NETWORK_TYPE`，实际注册 RAT
> 依旧由 modem NV 决定。AP 侧的 LTE_ONLY 障碍已清除，但要**真正回落到 2G/3G**
> 仍需按 v10 的 QPST 清单在 NV `Preferred Band` 放开 GSM 900 (P-GSM) /
> GSM 1800 (DCS)、保留 `Pref Service=CS and PS`，改前留 NV 快照、改后用
> `rf-check.py snapshot/compare` 验证。这属于基带配置，本次**未越界改 NV**；
> 若当地移动 2G 已退频，回落也不会发生，属客观无线条件。

### ③ 厂商后门：摘除 7 个无权限广播

`services.jar` 里 `LightsService.<init>` 动态注册的 7 个私有 action
（`android.led.sim.switch` / `android.led.sw.reset` / `android.led.power.off` /
`android.lte.get.cellinfo` / `android.intent.action.usbshare` /
`android.intent.action.apnrestore` / `com.youdo.restore`）做**等长重命名**
（末字符改 `z`），保持 dex `string_ids` 字典序。外部再发原 action 不再匹配，
`trafficThread` / `switchSimSlot()` 不会被拉起；正常广播
`BOOT_COMPLETED`、`CONNECTIVITY_CHANGE`、`SIM_STATE_CHANGED`、
`WIFI_AP_STATE_CHANGED` 全部保留。

重启实测：`dumpsys activity broadcasts` 中 7 个原 action 命中数**全为 0**，
只剩改名后的 4 个（其余 3 个本就不在 dump 中）；LED/短信/保活未受影响。

仍在线的**非 dex** 敞口（未处理，属独立决策）：
`ro.sys.usb.default.config` 含 diag、`ro.boot.selinux=disable`、`/res/keys`
已移除、`persist.sys.juzhen.*` 静态属性；以及非厂商的
`ro.adb.secure=0` + adbd 监听 `0.0.0.0:5555`、8080 面板 `admin:admin`。

### 补丁生成与部署（可复现）

`tools/jz02/patch-framework.py`：输入**本机原版** `services.jar` /
`telephony-common.jar`，输出 patched 两份。只改 `classes.dex` 内部字节并重算
dex header 的 adler32 checksum 与 SHA-1 signature，jar 其余条目原样保留。

字节级复核（改前/改后逐字节 diff）：

| jar | 差异 | 说明 |
|---|---|---|
| telephony-common | **26 B** | 24 B header + 2 B 常量 |
| services | **31 B** | 24 B header + 7 B 字符串 |

32111 个 `string_ids` 重新解析后排序无一出错，checksum / signature 均正确。

部署：patched jar 覆盖 `/system/framework/`，删
`services.odex` / `telephony-common.odex` 与 `/data/dalvik-cache` 对应缓存
（user 版全 odex，必须删，否则 dalvik 不会回落到 jar 内 dex），reboot。
上传用 helper `cgi-bin/cmd` 分块 base64（body ≤ 64 KB）。

**回滚包**（v13 收尾时已精简，见文末「收尾清理」）：设备
`/sdcard/Download/devcmp-rollback.tar.gz` 与本机 WSL（仓库外）
`~/msm8916-box-rollback/devcmp-rollback.tar.gz`，两者字节一致，
md5 `23bca8c91bf684d7bdcf35441db81921`、4,480,849 B、8 个成员：
原版 `services.jar` / `services.odex` / `telephony-common.jar` /
`telephony-common.odex` / 原 `install-recovery.sh` / `build.prop` /
`default.prop` / `init.rc`。`framework-res.apk` 全程未改动，无需备份；
patched jar 可用 `tools/jz02/patch-framework.py` 从原版重新生成。

### 带外恢复通道（临时，已关闭）

改 framework 前在 `install-recovery.sh` 的 shebang 之后临时插入一行
`busybox httpd -p 0.0.0.0:8898 -h /system/etc/relay-helper -f`（class main、
早于 framework，故 framework 起不来时仍可 root 恢复；token 校验保留）。
adb 通道不可用：本机定制 adbd 的 5555 不响应标准 ADB CNXN 握手，且本机无
adb 客户端。

重启实测 58s 时 8898 已监听、77s 面板 8080 起来。验证完成后已把
`install-recovery.sh` 恢复为正式版（md5 `3c8733e6…`）、kill 8898、端口关闭，
`/system` 回到 `ro`。

### 最终验证（`reboot` 后）

`/api/health` `healthy=true`（panel8080 / modemOnline / smsrelay / ssrZero /
memOk / helper / ril / adbd5555 / simReady 全 true）；keepalive
`fails=0 / lastPingOk=true / hipri=true`；`system_server` pid 871、
`gsm.sim.state=READY`、`gsm.network.type=LTE`、helper `pong`。

---

## v13：usbtethering 解包 / odex 副作用收口 / 开机 mdss 风暴前移修复

### ① SP970 `USBtether.apk` 解包 —— RNDIS 是功能，不是后门

`/system/app/USBtether.apk`（md5 `400affbd3bc60cc00d82cddd1e6980da`，29822 B），
包名 `com.example.usbtethering`，`sharedUserId=android.uid.system`，
`targetSdk=17`，带 `com.qualcomm.permission.USE_QCRIL_MSG_TUNNEL`。
Manifest：`.UsbDetact` receiver 监听 `ETHERNETTETHER_BROADCAST` 与
`BOOT_COMPLETED`；服务 `.USBTether` / `.USBunTether`。
**它是这台设备提供 RNDIS 网络共享的实现，不能禁用、不能删除。**

`classes.dex`：10180 B / 197 strings / 58 types / 79 methods / 14 classes。
逐指令反汇编（`r4_disasm.py`）的关键路径：

| 方法 | 关键指令 | 含义 |
|---|---|---|
| `UsbDetact.is_port_locked` | `execmd("getprop persist.sys.usb.config")` → `equalsIgnoreCase("charging,none")` / `("charging,none,adb")` | 只看持久化配置，**不读** `android_usb_state`，也不看 `rndis0` |
| `UsbDetact.onReceive` | `0084 invoke is_port_locked` → `0088 if-eqz v1,+19 → 0107 return-void` | **返回 1 才 `startService(com.example.USBTether)`**；「充电口配置」正是打开轮询的条件，不是跳过条件（名字有误导性） |
| `USBTether.onStart` | 同一个 `$1` 对象注册两个 filter：`"USB tethering"` 与 `ACTION_BATTERY_CHANGED` | 电量广播是 sticky，注册即触发，之后每次电量变化再触发 |
| `USBTether.onDestroy` | 仅 `super.onDestroy()` + `Log.d("ondestroy")` | 不显式 `unregisterReceiver`；但 Service Context 销毁时 framework 会清掉动态接收器，实测 `ReceiverList` 归 0 |
| `USBTether$1.onReceive` | `isMobileConnected()`（返回值被丢弃）→ `Thread.sleep(500)` → `getDeclaredMethods()` 遍历找 `setUsbTethering` → `invoke(Boolean.TRUE)` | 每来一次电量广播就反射强开一次 USB tethering |

当前插的是**供电口**：`android_usb_state=DISCONNECTED`、`usb/online=1`、
`battery/status=Discharging`、`rndis0 DOWN`、
`sys.usb.config=rndis,adb` 而 `persist.sys.usb.config=charging,none,adb`。
后者正好命中 `is_port_locked()` → 开机自动拉起轮询 →
`setUsbTethering(true)` 在无 USB 主机时永远不可能成功，于是每 ~20s 重复
（`TESTBYCHANGETHERNET`），伴生 `Tethering` churn 与
`QcrilMsgTunnelSocket IOException: Connection refused`。APK 全程没有判断
真实链路状态，这就是「原因」。

**处置：保留 RNDIS，只从外部补上它缺的那个判断。** 不动 APK、不动系统签名、
不停包、不 `pm disable`。在 `tools/relay-helper/start.sh` 加
`usb_tether_gate()`，由 30s 看门狗每轮执行：

- `state=CONFIGURED`（真 USB 主机/数据口）→ 确保 `.USBTether` 在跑
  （`am startservice -a com.example.USBTether`），RNDIS 与厂版完全一致；
- 其它状态（供电口/无主机）→ `am stopservice -a com.example.USBTether`；
- `ro.product.device=UFI`（JZ02）直接 return，不参与。

实测：`am stopservice` 后 `ServiceRecord` 与 `ReceiverList` 均归 0，
70s 观察窗口内 `setUsbTethering(true)` 0 次；`am startservice` 立即重建
ServiceRecord / 进程并恢复反射调用链。插真实数据线时 30s 内自动恢复 RNDIS。

### ② 上轮补丁的副作用：全量 odex 依赖表修复（已收口）

删 `services.odex` / `telephony-common.odex` 会让所有引用它们的 preopt odex
依赖表 stale。Dalvik odex `dey\n036\0` 的 deps entry 格式是
`[u4 nameLen][name][20-byte dex-header signature]`，其中 signature 是
**内嵌 dex 的 `SHA1(classes.dex[32:])`**，不是整个 odex 文件的 SHA-1；
`checksum = adler32(odex[depsOffset:])`，字段在 header offset 36。

- 灰度删单个 `voip-common.odex` → stale 13 → 25（连锁扩大），方案废弃并回滚；
- 提升 runtime dalvik-cache 作为两个 odex → 名字对上但 signature mismatch，
  由此校准了上面 20 字节的语义；
- 下载 `/system/**/*.odex` 共 80 个（44 MB tar 触发面板
  `/api/maint/file` 的 `OutOfMemoryError`，改 6 MB 分片下载拼接）；
- 分类 `ALREADY_FIXED 13 / NEED_DEPS_REBUILD 1 / NEED_SIG_FIX 57 /
  NO_TARGET_DEP 9`，等长替换旧 signature 并重算 checksum，
  `services.odex` 单独重建 deps chunk（同分区原子 `mv`）；
- 结果 `stale_count=0`，两次 reboot 后仍为 0。

### ③ 开机 mdss 风暴「补丁后仍在」的真正原因 + 前移修复

v7.2 的 `blank=4` 写在 `relay-helper/start.sh`。该脚本由
`qcom-post-boot`（`class late_start / disabled / oneshot`，
`on property:sys.boot_completed=1 -> start qcom-post-boot`）拉起，再
`sleep 90` → 实际落地 ~142s；而风暴从 `boot_progress_enable_screen`
（~43s）开始，43–142s 完全没被覆盖。

主修复前移到 `/system/etc/install-recovery.sh`（`flash_recovery`，
`class main`，早于 framework）：后台循环轮询 `fb0/blank`，等
`init.svc.surfaceflinger=running` 累计 5 次后开始写 `blank=4`，
每 1s 重断言共 240 次（覆盖 SF 的 `enable_screen` unblank 与旧 90s 兜底），
`i>90` 有硬兜底。`start.sh` 的 90s 补写与 30s 看门狗按
`show_blank_event` 补写保留为二道防线。写入 4 在已 blank 时是 no-op
（`show_blank_event` 不变、dmesg 无新事件），已实测。

重启实测（`dmesg` ring buffer 未回卷，oldest = `[ 0.000000]`）：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| `vsync poll timed out` + `frame timeout` | 508 + 508 | **4**（仅 0.98s / 17.15s 各一对） |
| 首次 `blank=4` | ~142s | **19.72s** |
| `mdss_fb0` stime | 30s 涨 ~10% 单核 | 30s **零增长**（stime = 1 jiffy） |
| 41s / 103s / 192s / 551s `panel_power_on` | 1 | 0 |

`/data/local/tmp/jz02-mdss-early.log`：
`first_write_uptime=19.72` / `writes=236 warm=240` / `last_uptime=264.29`。

### 回归与回滚

两台 `/api/health` 均 9/9。JZ02 `/system` 已由 fstab 恢复 `ro`
（SP970 是 eng 版，`/fstab.qcom` 里 `ro` 行被注释、`rw` 是原厂行为，
不是本次改动造成）。JZ02 三项固化全部保持：
`preferred_network_mode=9`、`sim=READY`、mux `0/0/1`、`stale=0`、
patched jar md5 `0e5954bc…` / `2fde1e6b…`、7 个原厂商 action 命中 0。
SP970 `USBtether.apk` md5 未变、包仍 `enabled`。

回滚：

- mdss：删 `install-recovery.sh` 的 early blank 块 + 注释 `start.sh` 两处补写
  → 回到 v7.2（风暴原样回来）；
- RNDIS 门控：注释 `start.sh` 的 `usb_tether_gate` 调用，或手工
  `am startservice -a com.example.USBTether` 立即恢复厂版行为；
- framework / odex：解包 `/sdcard/Download/devcmp-rollback.tar.gz`
  （或 WSL `~/msm8916-box-rollback/` 的同名副本），覆盖 4 个原版
  jar / odex，清 `/data/dalvik-cache` 后 reboot。**注意**：本轮把
  `/system/**/*.odex` 里 57 个依赖项的 20 字节 signature 改成了 patched
  dex 的签名，只换回 4 个原版文件会让这些 odex 重新 stale；完整回退需
  同步重建依赖表（或直接重刷固件，最可靠）。

本轮脚本：`tools/jz02/install-recovery.sh`（设备部署版 md5 `cea86053…`，
仓库修订版 `ce491c7c…`，差异仅为一行回滚注释路径，见「收尾清理」）、
`tools/relay-helper/start.sh`（md5 `98cbadca…`，两机同版）。

### 收尾清理（v13 后）

按用户决定「精简保留双份回滚包 + 一次性脚本全部不归档」执行：

- **回滚包**：`devcmp-backup/`（19.8M，11 文件）精简为上面的
  `devcmp-rollback.tar.gz`（4.3M，8 成员），设备上与 WSL 仓库外各一份，
  md5 双向校验一致；未改动的 `framework-res.apk`、2 个 patched jar
  （可由 `patch-framework.py` 重生成）不再保留。
- **设备端**：JZ02 删 67 个文件 / 6 个空目录（`devcmp-fetch/` 91.8M 含
  44.6M `all-odex.tar` + 8 个分片 + 13 个原始 stale odex、
  `devcmp-backup/`、顶层 `svc.dex`/`tc.dex`/`jz02-fw*.dex`/`PF.*`、
  `/data/local/tmp` 的 `_dx*`/`ir.*`/`relaytmp`/`r3all`/`led-locate`），
  `/sdcard` 用量 183.3M → 40.0M；SP970 删 8 文件 / 1 目录，107.4M → 99.1M。
  两机 `/data/local/tmp` 只剩运行中的 `relay-helper/` 与
  `jz02-mdss-early.log`；11/6–11/8 的 `smsrelay-*` 运维备份/日志未动。
- **本机**：`%TEMP%\devcmp\` 198 文件 / 165.9 MB 全清，54 个一次性
  取证与部署脚本（`devcmp_*`/`r3_*`/`r4_*`/`r5_*`/`mkpatch`/`dex*`/
  `upload_fw`/`deploy_fw` 等）全删，用户决定不把 `r3_fix_all.py`
  归档进仓库；odex 修补方法仅保留 v13 的文字说明与 signature 常量。
- **保留**：仓库仅 `README.md` / `install-recovery.sh` /
  `patch-framework.py` / `relay-helper/start.sh` 四个固化文件；
  `/system/framework/.r3tmp/` 是空目录，因 JZ02 `/system` 为 `ro`、
  重挂载会让 `system_server` mmap 后无法恢复只读，故不删（无任何影响）。
- 删除前以回滚包 md5 + 成员数做门控，分批 ≤10 文件并逐批校验
  `remain=0`；清理后两机 `/api/health` 仍 9/9。
- 同步修正失效路径：`install-recovery.sh` 第 37 行回滚注释与
  `patch-framework.py` docstring 由已删除的 `devcmp-backup/` 改指向
  `devcmp-rollback.tar.gz`。仓库版 `install-recovery.sh` md5
  `ce491c7cc23d5ce4b5d7687b53d89488`，设备 `/system/etc/install-recovery.sh`
  仍是部署时的 `cea8605360c5e8d9bf751563c4daefb6`，两者仅差这一行注释、
  功能完全一致。不重部署是为了不动 JZ02 的 `/system` 只读挂载
  （重挂载 `rw` 后需重启才能由 fstab 恢复 `ro`）。
