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

- **ril-daemon1（第二路 RIL）**：SP970 是 DSDS（`gsm.sim.state=READY,ABSENT`，
  卡 2 槽空），属电话链红线，不碰。
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

## 回滚

```bash
adb -s 192.168.10.196:5555 remount
for f in loc_launcher xtwifi-client xtwifi-inet-agent lowi-server location-mq mm-qcamera-daemon audiod imsdatadaemon imsqmidaemon ims_rtp_daemon ptt_socket_app dpmd ATFWD-daemon; do
  adb -s 192.168.10.196:5555 push tools/sp970/slim-backup/$f /system/bin/$f
done
# crontab 里删掉 am kill 行（面板「维护 → 定时任务」即可）
adb -s 192.168.10.196:5555 reboot
```
