# SP970 运行态精简（v1，2026-11-07）

> 设备：SP970-B（4GWiFi，msm8916，Android 4.4.4）、项目原宿主机、
> 网络 adb `192.168.10.196:5555`（USB 侧为 192.168.42.129:5555）。
> 同日 JZ02 侧先完成同族精简（见 `tools/jz02/README.md` v7/v7.2），本篇是 SP970 侧的平行操作。

## 做了什么

**13 个 `/system/bin` 二进制改名 `.disabled`**
（原件收在本目录 `slim-backup/`，不落设备侧备份；`adb remount` 后操作）:

| 目标 | 服务名 | 理由 |
|---|---|---|
| `loc_launcher` `xtwifi-client` `xtwifi-inet-agent` `lowi-server` `location-mq` | `loc_launcher` | GPS 全家 5 进程白跑（网棒无定位场景） |
| `mm-qcamera-daemon` | `qcamerasvr` | 无摄像头 |
| `audiod` | `audiod` | 无音频场景 |
| `imsdatadaemon` `imsqmidaemon` `ims_rtp_daemon` | 同名 | IMS/VoLTE 全家；SMS 走 CS 域与 IMS 无关（JZ02 对照组：同系固件 IMS 清零后照发照收） |
| `ptt_socket_app` | `ptt_socket_app` | Push-to-Talk 残留 |
| `dpmd` | `dpmd` | JZ02 同款默认不跑 |
| `ATFWD-daemon` | `atfwd` | AT 命令转发/远端诊断残留 |

**crontab 增补**：与 JZ02 共用 `tools/relay-helper/crontab`（am kill 缓存回收行
1,6,11,… 分钟）。首轮实测 MemFree 93→107MB。

## 明确不碰

- **电话链框架 / rild 二进制**：不改 `persist.radio.multisim.config` 让框架按
  单卡初始化（2026-11-09 实测会把主卡拖成 `NOT_READY`，见「永久停用第二卡槽」），
  不改 `/init.qcom.rc`（在只读 rootfs）、不替换 `/system/bin/rild`。
- **ril-daemon1（第二路 RIL）**：SP970 是 DSDS（`gsm.sim.state=READY,ABSENT`），
  第二卡槽**物理未焊接**，该路 RIL 纯空转，已按「永久停用第二卡槽」一节用
  crontab 守护跨重启停用；`simslot.py` 提供临时与永久两种操作，均可一键回滚。
- **display 栈**：SP970 的 DSI 链路是活的（MDSS GIC 104 中断自开机 142,436 个
  ≈11.7Hz 正常 vsync；JZ02 同数为 **0**，见 JZ02 README v7.2 对拍表），不能学
  JZ02 做 `fb0 blank=4`。
- 电话/RIL/com.android.phone、WiFi 端（hostapd/wpa_supplicant/dnsmasq）、
  relay-helper 全家、smsrelay（v1.8.3，watchdog-8080 依赖已满足）。

## 对拍速记（JZ02 vs SP970，2026-11-07）

| 项 | SP970 | JZ02 |
|---|---|---|
| CS 域登记 | `mServiceState=0 0`（健康） | 曾 NV `Pref Service=PS` 导致 OUT_OF_SERVICE，QPST 已修 |
| mdss vsync | 正常（~0.8% 单核） | 曾风暴 10.5% 单核 + 高温 4~5℃，已 `blank=4` 修复 |
| ksmd | 内核无此功能 | 曾 3.3% 单核，已 `run=0` |
| loadavg 地板价 | ~4.5（4 个 D 态内核线程） | ~5（5 个 D 态内核线程） |
| 本轮精简收益 | 13 个本机 daemons（~17MB RSS + fork 链） | 8 个本机 daemons + am kill 行 |

> 注：本 ROM 的 loadavg 被「常驻 D 态内核线程」结构性垫底（4.5~5.5），
> 与 CPU 热点相关性弱；跨机对比应以 CPU busy%（/proc/stat 采样）与温度为准。

## 验证记录

- 阶段一（运行时 stop）→ 10 分钟观测：零复活、`mServiceState=0 0` 不变、
  modem ONLINE、phone/smsrelay 存活、8080/8899 存活。
- 落盘（改名 13 + crontab/start.sh push，md5 与 repo 一致）→ `crond` 30s 重载，
  首轮 `am kill` MemFree 93→107MB。
- **重启复验（2026-11-07）全绿**：`boot_completed=1`、13 个目标进程零复活、
  9 个 `init.svc` 字段空（init exec 失败即放弃，与 JZ02 同款）、helper 全家在、
  `am kill` 行生效（MemFree 95MB，随 crond 逐轮回收）、modem ONLINE /
  SSR=0 / `mServiceState=0 0` / phone+smsrelay 双活 / 8080+8899 存活。

## 临时停用第二卡槽（运行时可逆，2026-11-09）

排查 JZ02 / SP970 射频差异时，需要确认空卡槽（第二路 RIL）是否影响主卡。
新增 `tools/sp970/simslot.py`：只做运行时 `stop/start ril-daemon1`，
**不改 `/system`、不改 persist 属性、不重启设备**，设备重启后自动恢复双卡。

```bash
python3 tools/sp970/simslot.py status   # 只读状态 + 健康检查
python3 tools/sp970/simslot.py off      # 停用，观察 20s 复活 + 主卡健康检查
python3 tools/sp970/simslot.py on       # 恢复
```

连接默认 `192.168.10.196:8080`，凭据沿用 `panel-e2e.sh` 的默认值；
可用 `--host` / `--auth` 或环境变量 `$RELAY_BASE` / `$AUTH` 覆盖。

等价手工命令（网络 ADB）：
`adb -s 192.168.10.196:5555 shell stop ril-daemon1` / `start ril-daemon1`。
`off` 会等 20 秒确认服务没被守护复活，再检查面板 / 主卡 `mServiceState` /
`com.android.phone` / `smsrelay` / 数据保活 / 信号读数；任一失败自动 `start` 回滚
（`--no-rollback` 可关闭该保护，不推荐）。

**实测（2026-11-09，SP970 192.168.10.196）**：

| 项 | 关闭前 | 关闭后 |
|---|---|---|
| `init.svc.ril-daemon1` | running | stopped（20s 零复活） |
| rild 进程 | 2 个（181 / 323） | 1 个（181），`rild1` socket 一并消失 |
| `com.android.phone` | pid 1027 | pid 1027 未重启，日志零崩溃 |
| 主卡 `mServiceState` | `0 0 home ... LTE` | 不变 |
| `/api/maint/checkup` | healthy | healthy（9 项 checks 全绿） |
| RSRP / RSRQ / SINR | -93 / -16 / -26 | -92 / -16 / -28 |

结论：空卡槽的第二路 RIL 不占射频资源，停用后主卡信号读数无实质变化，
不是两台设备 23 dB 差异的来源。

**注意**：运行时停用只杀进程，框架层仍是 DSDS——`gsm.sim.state` 依旧
`READY,ABSENT`，面板 `simSlots` 仍是两项、`netType` 仍是 `LTE,Unknown`。
想让框架按单卡初始化的 `ssss` 方案已实测否决（见下一节）；永久停用改用
crontab 守护，不改框架配置。

**回滚**：`python3 tools/sp970/simslot.py on`，或直接重启设备
（若已安装永久守护，重启后仍会保持停用，需先移除守护行）。

## 永久停用第二卡槽（跨重启，2026-11-09）

**硬件事实**：SP970-B 的第二 SIM 卡槽未焊接，卡 2 恒 `ABSENT`；但 ROM 按 DSDS
编译（`/system/build.prop:202` 写死 `persist.radio.multisim.config=dsds`），
`/init.qcom.rc:743` 的 `ril-daemon1`（`rild -c 1`，`disabled`）由 vendor RIL
（`libril-qc-qmi-1.so`）在开机时 `ctl.start`，纯空转。

### 先否决的方案：改 multisim 让框架按单卡初始化

`setprop persist.radio.multisim.config ssss` 写 `/data/property/` 覆盖
build.prop（与 `tools/hwfix` 的结论一致：改 build.prop 无效且会被覆盖），重启后：

| 项 | dsds（原厂） | ssss（单卡实验） |
|---|---|---|
| 面板 `simSlots` | `['READY','ABSENT']` | `['NOT_READY']`（确实单卡了） |
| `gsm.sim.state` | `READY,ABSENT` | `NOT_READY` |
| `init.svc.ril-daemon1` | running | 未启动 |
| `mServiceState` | `0 0 home ... LTE` | `1 1 ... Unknown`（OUT_OF_SERVICE） |
| 信号 | RSRP −93 | 无读数 |
| 健康检查 | 6 项全绿 | 主卡/保活/信号 3 项 FAIL |

等 120 秒仍不恢复。radio 日志：`PhoneFactory ... preferredNetworkType=7`
（GLOBAL；双卡残留 `preferred_network_mode="9,1"` 在单卡下无法解析）、
`qcril_uim ... Error reading iccid for slot 0`、IMSI/ICCID fetch failed、
`qcril_db_init ... readonly database`。即该 ROM 按 DSDS 编译并校准，
单卡路径会连主卡 UIM 一起初始化失败。
**结论：`ssss` 路径不可用，`simslot.py` 已移除该入口。**
实验期间已自动回滚两次，实验留下的 `/data/property/persist.radio.multisim.config`
也已删除，设备回到原厂 `dsds`。

### 采用方案：crontab 守护（框架保持 DSDS，第二路 RIL 永不驻留）

在 JZ02/SP970 共用的 `tools/relay-helper/crontab` 末尾加一条条件守护：

```cron
* * * * * [ "$(/system/bin/getprop init.svc.ril-daemon1)" = running ] && /system/bin/setprop ctl.stop ril-daemon1 >/dev/null 2>&1
```

- 设备侧经面板 `/api/maint/cron` 写入，`tools/relay-helper/start.sh` 30 秒内
  自动重载 busybox crond；开机后最多 60 秒内停掉第二路 RIL 并持续保持。
- 条件是「服务 running 才停」，JZ02 没有该服务、条件天然为假，共用 crontab 零副作用。
- 不改 `persist` 属性、不改 `/system/bin/rild`、不动 boot 分区、不刷机。
- `simslot.py` 内置同文守护片段；**改动时两处（脚本 `GUARD_BLOCK` 与
  `tools/relay-helper/crontab`）必须同步**。

```bash
python3 tools/sp970/simslot.py status
python3 tools/sp970/simslot.py permanent-off                   # 装守护 + 立即停用
python3 tools/sp970/simslot.py permanent-off --verify-reboot   # 再重启验证（会断网）
python3 tools/sp970/simslot.py permanent-on                    # 一键回滚
```

### 重启复验（2026-11-09，SP970 192.168.10.196）

| 项 | 重启后实测 |
|---|---|
| `persist.radio.multisim.config` | `dsds`，`/data/property` 无覆盖文件（纯原厂） |
| `init.svc.ril-daemon1` / rild 进程 | `stopped` / 1 个（主卡） |
| 守护链路 | crond pid 1269，`cron.d/root` 含守护行 1 条 |
| 主卡 | `READY,ABSENT`、`0 0 home CMCC 46000 LTE`、RSRP −93 / RSRQ −16 |
| 健康检查 | 6 项全绿；`/api/maint/checkup` 9 项全绿（含 `simReady`/`ril`/`modemOnline`） |

**限制**：框架仍按 DSDS 初始化，面板 `simSlots` 仍是两项、卡 2 显示 `ABSENT`；
被关掉的是那路 RIL 进程，不是框架里的空槽记录。这是保持主卡可用的必要代价
（单卡路径已证伪）。恢复出厂会重置 `/system`，用 `tools/deploy-helper.py`
重新部署即可（仓库 crontab 已含守护行）。

**回滚**：`python3 tools/sp970/simslot.py permanent-on`（删守护行 +
`start ril-daemon1`）；或面板「维护 → 定时任务」删掉那 4 行注释 + 1 行任务，再
`adb -s 192.168.10.196:5555 shell start ril-daemon1`。

## 回滚

```bash
adb -s 192.168.10.196:5555 remount
for f in loc_launcher xtwifi-client xtwifi-inet-agent lowi-server location-mq mm-qcamera-daemon audiod imsdatadaemon imsqmidaemon ims_rtp_daemon ptt_socket_app dpmd ATFWD-daemon; do
  adb -s 192.168.10.196:5555 push tools/sp970/slim-backup/$f /system/bin/$f
done
# crontab 里删掉 am kill 行（面板「维护 → 定时任务」即可）
adb -s 192.168.10.196:5555 reboot
```
