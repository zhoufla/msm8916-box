# JZ02 适配层（msm8916-box 本地 fork）

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
| root 通道 | web 控制台写回 adbkey + post_boot 注入 | **v3 全量包 `ro.secure=0` → adb shell 直接 root** | 部署脚本直接 `adb remount` |
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

### 1. 刷入 v3 全量包（一次性完成「强化精简 + 永久 root adb」）

原厂 aboot 的 fastboot 只实现 `getvar / download / reboot`，**`flash` / `erase` / `boot` / 甚至 `reboot` 全部返回 `unknown command`**，
所以 fastboot 直刷分区不可行。走 MiKo：

```
F:\wifi\jz02\BAK-modified\ja02-fullbak-hardened-rooted.bin
md5 65507392b8c96bc67163e89da22ffc01
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
