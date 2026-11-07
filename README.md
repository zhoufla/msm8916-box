# msm8916-box — MSM8916 随身 WiFi 管理套件

面向 MSM8916 平台随身 WiFi 棒子（SP970-B 等，Android 4.4.4 eng/root）的设备管理套件。
**短信网关**是第一个模块，**内嵌 Web 面板**是第二个：浏览器直连设备，收发短信、看系统状态、
管热点，不需要装任何客户端。

> 定位：把一根只当热点用的棒子，变成可远程管理的短信网关 + 设备面板。
> 理论上通用于同平台（MSM8916 / 8916 系）刷了原厂安卓的随身 WiFi 设备。

## 当前功能

| 模块 | 说明 |
|---|---|
| `SmsReceiver` | 监听 `SMS_RECEIVED`（优先级 999），不抢占默认短信 App，4.4 收到广播即转发 |
| `RelayService` | 常驻前台服务：扫库 → 多通道并发投递 → 标记成功/失败；同时托管 Web 面板 |
| `Db` | SQLite 三表：`ev`（收信本体，号码+内容+命中规则+90s 窗口去重）+ `dl`（消息×通道投递状态，带规则优先级与模板覆盖，退避 1/2/4/8/16/30min，上限按通道配置）+ `out`（发件记录） |
| `Sender` | 钉钉（text 或 markdown 卡片，可选 SEC 加签）/ 通用 webhook（JSON + 可选 X-Token），多设备标签，UTF-8 |
| `SmsSender` / `SendReceiver` | 面板发短信：分片发送，逐片回写结果 |
| `Inbox` | 补收：把系统收件箱里漏转发的短信补进队列 |
| `AppConfig` | 配置优先级：`/sdcard/smsrelay.conf`（JSON）> SharedPreferences > 内置示例 |
| `WebPanel` / `Http` | 内嵌 HTTP/1.1 服务（纯 JDK，无第三方库）+ JSON API + 静态页面，Basic 鉴权 |
| `SysInfo` | 型号/基带/信号（rsrp+rsrq+rssnr+制式，PhoneStateListener 缓存不额外耗电）/电量/存储/流量/uptime/网卡 |
| `WifiCtl` | 热点启停与 SSID/密码、station 开关、驱动与硬件区分、网卡 MAC/IP、ARP 客户端、扫频 |
| `Update` / `Net` | 在线升级：GitHub Release 或任意 manifest；sha256 + 签名 + versionCode 三重校验；依次尝试 su / 机器自带 web 后台 root 通道 / 系统安装器 |
| `Logs` | 内存环形日志，面板「最近日志」直读 |
| `MobileKeepalive` | LTE 短信保活：WiFi 在线时保持一条 `MOBILE_HIPRI` 链路，每 10s 在 `rmnet0` 上发一个 ICMP 包，解决基带空闲态漏寻呼导致的短信延迟（详见下节） |
| `TestReceiver` | 调试通道（见下） |
| 保活 | 前台通知 + START_STICKY + AlarmManager 5min 兜底 + PARTIAL_WAKE_LOCK |

转发格式默认三行（通道里可用 `template` 改，占位符 `{peer} {body} {device} {tag} {time}`）：

```
+10086
<短信正文，多行原样保留>
设备：158
```

验证码类短信自动提取 4~8 位数字进 `code` 字段。

### LTE 短信保活（`MobileKeepalive`）

SP970-B 的 2015 年 MPSS 基带在 LTE 空闲态（ECM-IDLE）会漏掉网络寻呼：手机信号、
注册状态都正常，但短信要等几分钟，或者等下一次主动上网才集中下来。这是基带固件问题，
换 APN/换卡都无效。

保活思路（与 `SMSRelay8916` 的 `smsrelay-keepalive` 一致）：

1. WiFi 仍是默认上行，不切移动数据；
2. 通过 `ConnectivityManager` 的 `MOBILE_HIPRI` 特性额外拉起一条移动数据 PDP（`rmnet0`）；
3. 每 10 秒以 root 身份执行 `busybox ping -I rmnet0 -c 1 -W 3 223.5.5.5`，让基带保持连接态；
4. 把 `android.telephony.apn-restore` 设为 `2000000000`，绕过 4.4
   `ConnectivityService$FeatureUser` 默认 60 秒过期（否则链路每分钟被拆一次）；
5. 连续 6 次 ping 不通时自动停/启 HIPRI 重建链路（5 分钟冷却）。

开机后基带/数据附着通常要 1~2 分钟，这期间 `startUsingNetworkFeature` 可能返回 `2`
（`APN_TYPE_NOT_AVAILABLE`），线程会每 10 秒重试，附着完成后自动连上，不需要手工干预。

配置段（`/sdcard/smsrelay.conf`，缺省即开启）：

```json
"keepalive": {
  "enabled": true,
  "intervalSec": 10,
  "target": "223.5.5.5",
  "iface": "rmnet0",
  "restoreMs": 2000000000
}
```

- **流量**：约 0.5 MB/天，需要 SIM 有可用数据流量；没有流量套餐时把 `enabled` 设为 `false`。
- **依赖**：ping 需要 raw socket，app uid 没有权限，所以必须走 `relay-helper` root 通道；
  helper 不在线时保活会记录错误并等待恢复。
- **注意**：`ping -I <rmnet0 的 IP>` 在这台机器上会走 wlan0、100% 丢包，必须用网卡名 `-I rmnet0`。
- 面板「概览 → 蜂窝与信号」会显示保活状态；`GET /api/keepalive` 返回完整状态。
- 面板「维护 → LTE 保活」可直接改开关/间隔/目标/网卡/`restoreMs` 并保存，保存后经
  `RelayService.wake()` 立即生效（间隔/目标/开关都不用重启服务）。

### 告警推送（`Alerts`）

设备侧自己出问题不能靠「人去看面板」发现，所以阈值告警主动推到短信转发通道（合成
`peer="ALERT"` 的消息，复用 `Sender`；不进转发记录，推送失败只记日志，绝不影响转发主链路）：

- **事件源**：体检判据异常（modem SSR / RIL / SIM / 内存 / 面板 8080 / 进程，同一套
  `Maint.checkup` 判据）、流量月配额（`trafficGb`>0 时启用，按「流量清零」以来的累计收+发）、
  低电量（`batteryPct`>0 时启用，仅未充电时告警）、LTE 保活重建与恢复（即时事件，
  不受检查间隔限制）。
- **推送语义**：状态**翻转才推**——进入异常推一次、恢复推一次；持续异常每 `remindH`
  小时（默认 6h）重复提醒一次。进程重启后状态清零，重启后首个周期会重新推一次。
- **配置**（`alerts` 段，面板「维护 → 告警推送」卡片）：

```json
"alerts": {"enabled": false, "channels": [], "remindH": 6, "intervalMin": 5,
           "trafficGb": 0, "batteryPct": 0, "keepalive": true}
```

- `channels` 留空 = 推给全部启用通道；填通道名列表则只推这些（引用已删除的通道会被跳过）。
- 默认关闭（`enabled: false`），避免升级后突然给钉钉群里发消息。

### DDNS 动态域名（`Ddns`）

出门在外要回访面板，但出口 IP 随时会变。DDNS 把当前可达地址推到域名服务商：

- **地址来源**：station 模式报 NAT 出口 IPv4（api.ipify.org 探测）；IPv6 没有中间 NAT，
  直接报接口上的全球单播地址（默认 rmnet 优先、其次 wlan；`iface` 可指定网卡，
  指定后只看这一张）。蜂窝 IPv4 是运营商 CGNAT
  （100.64/10），公网不可达，**不报、不误导**。`family` 选择推 v4 / v6 / 两者（默认 both）。
  面板状态行实时显示「当前 v6」（本地枚举，不用等推送成功）；`iface` 指定时还显示该网卡 IPv4。
- **多条解析**：`records` 数组最多 20 条，每条独立的 `domain` + `iface` + `family`；
  服务商/Token/Zone 全局共用一份。面板有独立「DDNS」页签（表格逐行添加/删除）。
  旧的单条 `domain/iface/family` 配置自动迁移为 `records[0]`。每次更新逐条写日志
  （`已更新 域名@网卡 → v4/v6`），失败汇总经「告警推送」通知。
- **服务商**：DuckDNS（GET 一把带 `ip=`/`ip6=`）与 Cloudflare API v4（查 zone →
  A/AAAA 记录有则 PATCH 无则 POST，TTL 300）。HTTPS 走 `Net`（自写 TLS 客户端，
  握不上手退系统 curl——与自升级同一条链路）。
- **推送策略**：变更才更新（v4|v6 成对比对），`intervalMin`（默认 10 分钟）兜底巡检；
  更新失败经「告警推送」通知（含恢复通知）；面板 Token 框留空保存=保持原值。
- **配置**（`ddns` 段，面板「维护 → DDNS 动态域名」卡片）：

```json
"ddns": {"enabled": false, "provider": "duckdns", "domain": "mybox",
         "token": "", "zone": "", "intervalMin": 10, "insecure": false,
         "iface": "", "family": "both",
         "records": [{"domain": "mybox", "iface": "", "family": "both"},
                     {"domain": "mybox6", "iface": "rmnet0", "family": "v6"}]}
```

### 通道字段

| 字段 | 说明 |
|---|---|
| `name` | 唯一标识，投递记录按它区分 |
| `type` | `dingtalk` / `webhook` |
| `url` | 必须 http(s) 开头；为空则该通道不投递 |
| `secret` | 钉钉加签（SEC 开头），配了才带 timestamp/sign |
| `token` | webhook 的 `X-Token` 请求头 |
| `template` | 正文字模板，默认 `{peer}\n{body}\n设备：{device}`（第一行发件人、第二行内容、第三行设备名）。占位符：`{peer}` `{body}` `{code}` `{device}` `{tag}` `{time}`。**验证码消息默认前置**：识别出验证码且通道/规则都没自定义模板时，自动改用 `【{code}】{peer}\n{body}\n设备：{device}`；自定义了模板（通道或规则）则完全按自定义 |
| `format` | 钉钉专用：`text`（默认）/ `markdown` 卡片 |
| `title` | markdown 卡片标题，空则自动「短信 · 设备 · 号码」 |
| `tag` | 多设备标签：写进钉钉卡片标题与 webhook 载荷；要在正文里出现就用 `{tag}` 占位符 |
| `maxTries` | 该通道失败重试上限（1~50，默认 10） |
| `enabled` | 是否启用 |

### 转发规则（短信规则引擎）

收到的短信按 `rules` 数组**顺序**求值，命中规则的通道并入投递目标（同通道取最高优先级）；
没有规则命中时按 `rulesDefault` 兜底：`all`（默认，投给全部启用通道，向后兼容）或 `drop`。

```json
"rulesDefault": "all",
"rules": [
  {"name": "otp",  "peer": ["^106\\d+$", "^955"], "body": ["验证码|code"],
   "channels": ["运维群"], "priority": 100, "stop": true},
  {"name": "bank", "peer": ["95588", "95533"], "channels": ["银行群"]},
  {"name": "ads",  "body": ["退订|优惠|贷款"], "action": "drop"}
]
```

| 字段 | 说明 |
|---|---|
| `name` | 必填且唯一（保存时校验），转发记录与日志按它标记 |
| `enabled` | 缺省 `true` |
| `peer` / `body` | 正则数组（部分匹配，不强制整串）；数组内任一命中即算命中该字段，两个字段同时给时是 AND |
| `channels` | 命中后投递的通道名列表；`action: drop` 时可省 |
| `priority` | 0~1000（默认 0）。投递循环按优先级先发验证码，失败重试也不会排到普通消息后面 |
| `template` | 命中该规则的投递用这个正文模板覆盖通道模板（占位符同通道字段表） |
| `from` / `to` | 生效时段 `HH:mm`，支持跨零点（如 `23:00`→`04:00`） |
| `stop` | 命中后不再评估后续规则 |
| `action` | `relay`（默认）/ `drop`。drop = 照常入库并记录命中的规则名，只是不投递（转发记录里显示「已丢弃」） |

- 数据库升级到 VER=4：`ev` 多一列 `rule`，`dl` 多 `prio`/`template` 两列，老库启动时自动
  `ALTER TABLE`，无需手工迁移。
- 规则只管**自动路径**（短信广播、补收）。人工动作有显式意图，不走规则：
  面板「重发」与注入测试、系统收件箱单条转发都投给全部通道。
- 面板：**短信 → 转发配置** 页新增「转发规则」卡片——JSON 文本框 + 保存前校验 +
  匹配测试器（输入号码/正文，显示命中哪条规则、投给谁、优先级多少）。
- API：`POST /api/rules/validate`（只校验不落盘）与 `POST /api/rules/test`（现拼一条事件
  跑规则，回显命中链与投递目标，不落库）。

## Web 面板

服务启动即拉起面板，默认 `http://192.168.42.129:8080`（8080 被占用自动顺延到 8085，
实际端口见常驻通知或 App 界面）。默认账号 `admin / admin`，在配置的 `web` 段里改：

```json
"web": {"enabled": true, "port": 8080, "user": "admin", "pass": "admin"}
```

`web` 段还有两个可选字段：

- **`token`（访问令牌）**：配置后 `Authorization: Bearer <token>` 与
  `?access_token=`（EventSource 场景）可与账号密码并存，适合 curl / Home Assistant /
  快捷指令等外部集成；恒时比较，留空=仅账号密码。面板「维护 → 设备与面板」可一键生成。
- **`cors`（允许来源）**：配置了才回 `Access-Control-Allow-Origin` 并放行 OPTIONS 预检；
  `"*"` 放行任意来源，数组=精确匹配（忽略大小写，最多 16 条）。留空=不发 CORS 头（原行为）。

八个页面：**概览**（队列/设备/信号/最近日志）· **短信**（三个子页签，列表均分页每页 20 条：
**系统收件箱** 只读浏览系统短信 + 已转发标记 + 单条转发 / 补收全部 · **短信发送** 发短信 +
发送记录 · **转发配置** 转发记录（收信 + 每通道投递状态、命中规则标记、重发、注入测试、清理已放弃）+
通道增删改 + 转发规则（JSON 编辑 + 校验 + 匹配测试）+ 配置文件来源）·
**系统**（硬件/系统/存储/流量/网卡）· **WiFi**（热点开关与 SSID/密码、station 开关、
无线网卡与 MAC/IP、客户端、扫频、隐藏 SSID/关联上限/踢客户端）· **网络**（APN、网络制式、
数据/漫游开关、SIM 锁、流量统计、网络诊断）· **升级** · **维护**（root 命令框、体检、日志、
备份、电源、定时任务、LED、文件下载、恢复出厂）。

页面是单文件、无外链 CDN，设备没网也能打开；XHR + ES5，老浏览器也能用。

### API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 概览：设备、面板、通道、队列计数 |
| GET | `/api/events` | SSE 实时推送概览状态（变更即推 + 15s 心跳 + 30min 重连），连接上限 3 |
| GET | `/api/ddns` | DDNS 状态：配置 + 上次推送地址/时间/错误 |
| POST | `/api/ddns/now` | 立即触发一次 DDNS 更新（后台执行） |
| GET | `/api/keepalive` | LTE 保活状态：开关、HIPRI、rmnet0 地址、最近 ping、失败次数 |
| GET | `/api/sys` | 系统信息全量 |
| GET | `/api/messages?page=1&page_size=20` | 转发记录分页（收信 + 每通道投递状态），回 `total/page/page_size`；旧 `limit` 参数仍兼容 |
| GET | `/api/sysinbox?page=1&page_size=20` | 系统收件箱只读分页，每条带 `relayed` 已转发标记；ROM 不给总数时 `total=-1` |
| POST | `/api/sysinbox/forward` | 单条转发系统收件箱短信（`{"peer","body","ts"}`），走 90s 窗口判重防重复投递 |
| POST | `/api/messages/retry` | 重发（`{"id":123}`），顺带补建新通道的投递 |
| POST | `/api/messages/test` | 注入测试消息（`{"peer","body"}`） |
| POST | `/api/inbox/import` | 补收系统收件箱 |
| POST | `/api/messages/delete` | 删收信：`{"id":123}` 单条（连投递一起删），`{"scope":"givenup"}` 清全部「已放弃」投递及因此变空的消息 |
| POST | `/api/send` | 发短信（`{"to","body"}`） |
| POST | `/api/rules/validate` | 校验转发规则 JSON（`{"rules":[...]}`，可选 `"channels"` 用外部通道名集合），坏正则/重名/越界回 400 |
| POST | `/api/rules/test` | 规则匹配测试（`{"rules":[...],"peer","body"}`，可选 `"channels"`/`"rulesDefault"`），回命中链与投递目标，不落库 |
| GET | `/api/outbox?page=1&page_size=20` | 发件记录分页，回 `total/page/page_size`；旧 `limit` 参数仍兼容 |
| POST | `/api/outbox/delete` | 删发件记录（`{"id":3}`，只删记录不动已发的短信） |
| GET | `/api/config` | 当前生效配置 + 来源文件 + 面板状态 |
| POST | `/api/config` | 保存配置（body 为完整配置 JSON） |
| GET | `/api/wifi` | 热点 + station + 网卡 + 客户端 + `hint` |
| POST | `/api/wifi` | `{"action":"ap_on\|ap_off\|ap_set\|sta_on\|sta_off","ssid","pass"}`（`on/off/set` 兼容旧面板），回 `{ok,message,state}` |
| GET | `/api/wifi/scan` | 扫频，回 `{ok,list,hint}`（station 开启时才有结果） |
| GET | `/api/update` | 当前版本 + 上次检查结果 + 更新源配置 |
| POST | `/api/update/check` | 查最新版本（结果落缓存，面板直接读） |
| POST | `/api/update/apply` | 下载 + 校验 + 安装 |
| POST | `/api/update/config` | 改更新源：`{mode,repo,manifest,auto,intervalH,insecure}` |
| GET | `/api/update/probe?host=` | TLS 诊断：本机支持的协议/套件 + 实际握手结果 |
| GET | `/api/logs?limit=80` | 内存日志 |
| GET | `/api/health` | 轻量健康探针（8080 / SIM / RIL / SSR / modem / 内存） |
| GET | `/api/maint/helper` | helper 状态、地址、token、执行命令的 uid |
| GET/POST | `/api/maint/config` | 读写 `helper` / `maint` 配置段 |
| POST | `/api/maint/cmd` | 通用 root 命令框：`{"cmd":"id"}` |
| GET | `/api/maint/checkup` | 一键体检（与 `boot-window-check.py` 同一套判据） |
| GET | `/api/maint/logs` | `?kind=main\|radio\|kernel\|relay\|all&filter=&lines=200` |
| POST | `/api/maint/logs/bundle` | 打包 logcat/dmesg/props/ps/netstat 到 `/sdcard/Download` |
| POST | `/api/maint/backup` | 打包 conf + shared_prefs + relay.db + softap.conf + 属性 |
| POST | `/api/maint/restore` | 从设备上已有备份包恢复（需 `confirm:"RESTORE"`） |
| GET | `/api/maint/files` | 列出 `/sdcard/Download` |
| GET | `/api/maint/file?path=` | 二进制下载（走 `Resp.bytes()`，不做 multipart 上传） |
| POST | `/api/maint/power` | 重启 / 关机 / bootloader（需 `confirm` 大写动作名） |
| POST | `/api/maint/led` | LED 亮 / 灭 / 闪 8 下 |
| GET/POST | `/api/maint/cron` | 读写设备侧 crontab，30s 内自动重载 crond |

`/api/wifi` 的 `state` 字段（真机实测值）：

```json
{
  "ap": true, "wifiOn": false, "driver": true, "hardware": true,
  "ssid": "ufi_56423", "secured": true, "apIp": "192.168.0.1",
  "ifaces": [{"name":"wlan0","mac":"08:97:58:1a:af:da","ip":"192.168.0.1","up":true}],
  "station": {"connected": false, "ssid": "", "rssi": 0, "level": 0, "linkSpeed": 0,
              "mac": "08:97:58:1a:af:da", "ip": ""},
  "clients": [{"ip":"192.168.0.50","mac":"aa:bb:cc:dd:ee:ff","iface":"wlan0"}],
  "hint": "热点已开启（单射频，此时无法扫频；要扫描请先关热点）"
}
```

- `driver` = wlan 模块**当前已加载**；`hardware` = 机器上**有** WiFi（wlan.ko 存在）。
  WiFi 全关时 `driver=false`、`ifaces=[]` 是正常的（4.4 按需 insmod/rmmod）。
- `apIp` 是热点地址（本机 `192.168.0.1/24`），`station.ip` 只放连路由器拿到的地址，
  两者不混用，避免热点模式下误以为「已连网络」。
- `hint` 说明当前为什么干不了活（模块没加载 / 热点占着射频 / station 没开）。
- **WiFi 与热点互斥**：驱动只有一颗射频、一个模式开关
  `/sys/module/wlan/parameters/con_mode`（0=STA、1=AP）。热点开着时 `sta_on` 直接回
  `ok:false` 并说明「请先关热点」；`ap_on` 会自动先关 station，并在 `message` 里注明
  「已自动关闭 WiFi」。面板失败提示统一取 `message`（后端业务失败用 `message`，HTTP 层错误用 `error`）。

curl 示例：

```sh
curl -u admin:admin http://192.168.42.129:8080/api/status
curl -N -u admin:admin http://192.168.42.129:8080/api/events   # SSE 实时推送，Ctrl-C 退出
curl -u admin:admin -X POST -d '{"to":"10086","body":"1"}' http://192.168.42.129:8080/api/send
```

配置了 `web.token` 后，外部系统（Home Assistant / 快捷指令 / 脚本）可只用令牌调全部 API，
含 `/api/send` 对外发信接口（不需要 Basic 弹窗）：

```sh
curl -H "Authorization: Bearer <token>" \
     -d '{"to":"10086","body":"1"}' http://192.168.42.129:8080/api/send
```

## 维护通道：relay-helper（替代 webkey 的地基）

按 `sp970-full-replacement-feature-list.html` 的 B0 + B1 实施。**所有特权能力共用一个
通用 root 命令通道**，而不是逐功能写白名单 CGI：加功能只改前端/app 一层，不用重新部署 /system。

```
浏览器 :8080  →  WebPanel  →  Root.java  →  127.0.0.1:8899 busybox httpd + CGI  →  uid=0 sh
                                                  ↑ init(root) 开机拉起 + 30s 看门狗
```

| 项 | 落地位置 |
|---|---|
| 开机自启 root 通道 / 看门狗 | `tools/relay-helper/start.sh` + `post_boot.snippet`；插入点必须在 `setprop service.adb.tcp.port 5555` **之前**（后面两个长循环永远跑不到） |
| 通用命令 / 二进制下载 | `cgi-bin/cmd`（POST body = 命令，末行 `[exit=N]`，输出上限 256KB / 90s）、`cgi-bin/get`（只允许 `/sdcard/Download/`） |
| app 侧客户端 | `Root.java`：超时、输出截断、退出码、`uid()`、`alive()`、`file()` |
| 一键静默升级 | `Update.install()` 改用 `Root.exec()`；`pm install` 与 `am start` 仍拼同一条命令 |
| 命令框 / 体检 / 日志 / 电源 / 备份 / 文件 / cron / LED | `Maint.java` + `/api/maint/*` + 面板「维护」tab |
| 健康自检 | `/api/health` + `Maint.tick()` 定时落盘 + crond 每天 04:00 快照 |
| 定时任务 | `crontab` 由面板编辑，看门狗检测变化后重载 `busybox crond` |

设备侧真机实测踩到的坑（都已写进代码注释）：

- busybox httpd 的 CGI 响应头必须用**纯 `\n`** 结尾；用 `\r\n` 会把正文当 HTTP 头转发。
- 这台 4.4 的 toolbox 没有 `printf` / `head` / `tail` / `nohup` / `setsid`，一律用
  `/system/xbin/busybox` 的 applet；后台进程必须三个 fd 全重定向，否则 `adb shell` 不返回。
- `flock -n 9` 在这台 `/system/bin/sh` 上报 `Bad file descriptor`，单实例改用 pidfile + `/proc`。
- Android 原厂没有 `/etc/passwd`，`getpwnam("root")` 失败导致 crond 报
  `ignoring file 'root' (no such user)`；部署脚本会补一条最小记录。
- `busybox` 与 helper 放 `/system`（`/data` 带 nosuid 且可能被清），不依赖 `/data/local/tmp`。
- `post_boot.sh` 已纳入仓库版本化（`tools/relay-helper/init.qcom.post_boot.sh`），
  设备改动丢失时可以重放部署。

### v1.8.1 稳定性兜底（2026-11-07 事故后补）

那次面板卡死只能手动拔电：内核没有 `/dev/watchdog`、`apps_wdog_bark` 从未触发，
也没有 ramoops/pstore（`/proc/last_kmsg`、`/sys/fs/pstore` 都不存在），
内存环形日志一断电全丢；App 内 5 分钟 `RTC_WAKEUP` 闹钟在系统半卡死时没有投递，
且原本只做 `startService()`——服务还活着就只走 `onStartCommand`，
WebPanel「假在线」（内部 running=true、端口不再 accept）永远不会被重建。
另外 RTC 断电归零、开机 NITZ 校时会把墙钟往前跳约 34 小时，墙钟基准的闹钟/超时不可靠。

| 层 | 实现 | 产物 / 开关 |
|---|---|---|
| 现场取证 | `Logs.add` 同步追加落盘 + 256KB×3 轮转，每行带 `up=` 单调秒；`bin/logsnap.sh` 每 2 分钟覆盖式留一份 dmesg + logcat 尾部（先写 .tmp 再 mv，拔电不破坏上一份） | `/sdcard/Download/smsrelay-run.log[.1-.3]`、`smsrelay-lastkmsg.log` |
| App 内第一道 | `AlarmManager.ELAPSED_REALTIME_WAKEUP` 每 5 分钟 → `AlarmReceiver` → `Watchdog.probeAndRepair()`；探免认证 `GET /api/ping`，失败 `WebPanel.restart()` | 端口文件 `/sdcard/Download/smsrelay-port` |
| helper 第二道 | `bin/watchdog-8080.sh` 每 2 分钟探 `/api/ping`：fail=1 发 `WATCHDOG_TICK` 广播 → fail=2/3 `am force-stop` + `am start -n …/.MainActivity` + 广播 → fail=4 `reboot` | 日志 `smsrelay-watchdog.log`；`touch smsrelay-no-reboot` 可禁用自动 reboot |

- **端口文件双重作用**：面板 8080 被占用会自动顺延，helper 据此探实际端口；
  文件不存在 = 面板被有意关闭（`web.enabled=false`），helper 直接跳过，不会误 reboot。
- 内部计时（保活、SSE、DDNS tick、告警节流、命令超时）统一 `SystemClock.elapsedRealtime()`；
  短信时间戳、`lastPingAt/lastOkAt` 等展示值和持久化 `checkedAt` 仍用墙钟。
- **`force-stop` 后必须用带 `--include-stopped-packages` 的广播 + `am start`**：Android 3.1+
  默认给普通广播加 `FLAG_EXCLUDE_STOPPED_PACKAGES`，被 force-stop 的包处于 stopped 状态，
  静态 `WatchdogReceiver` 收不到；只有 `am start` 能清掉 stopped 并让 `MainActivity.onCreate`
  重新 `startService`。实测广播 `flg=0x20` 一轮即可在 6s 内拉回面板。
- 两个 root 脚本**全程不用管道**：`dmesg | tail` 这类写法会让上游吃 SIGPIPE，
  在 `/data/tombstones` 刷 tombstone，反而给已高负载的设备添乱。
- helper 脚本随 `tools/deploy-helper.py` 部署；`crontab` 改动会被 start.sh 在 30s 内自动重载。
- v1.8.1 已于 2026-11-07 在 `192.168.10.196` 真机部署，L1 `panel-e2e.sh` 72/72，
  WD-01/02/03/05/06/07 实测通过（详见 `tools/TEST-PLAN.md` 第 9 节）。
- 注意：这两层都救不了「内核完全冻结」——真硬件看门狗需要内核启用
  `CONFIG_MSM_WATCHDOG` bite 或外部断电装置，本版本只能保证「内核还能调度 shell」时自愈。

## 在线升级（GitHub Actions 云编译 + 设备主动拉取）

打一个 tag 就出一条 Release，设备在面板「升级」页点一下，或到点自动查（默认 24h）。

```sh
git tag v1.3.0 && git push origin v1.3.0
```

`.github/workflows/build-release.yml`：装 JDK 17 + `platforms;android-19` + `build-tools;34.0.0`
→ 跑离线测试 → `VER=<tag> VC=<major*10000+minor*100+patch> ./build.sh`
→ 用仓库里的 `debug.keystore` 签名（与既有安装同签名，`install -r` 不丢数据）
→ 发布 `com.sp970.smsrelay-<ver>.apk` + `latest.json`（含 sha256）。

设备端两种取法（面板可切）：

| mode | 取法 | 备注 |
|---|---|---|
| `github` | 先走 `api.github.com/repos/<owner>/<repo>/releases/latest`；APK 优先用 `browser_download_url`（github.com 直链，不占 API 配额），失败再退回 `/releases/assets/<id>` | 未认证的 API 是同一出口 IP **每小时 60 次**，用完就 403；限流或 api.github.com 不通时，自动改读 `https://github.com/<owner>/<repo>/releases/latest/download/latest.json`，再从直链下 |
| `manifest` | 任意 JSON：`{"version","url","sha256","size","notes"}` | `url` 也可以写裸文件名，按清单所在目录补全（`build.sh` 产出的 latest.json 里就是裸文件名 `apk`）；适合自建镜像 / 内网 / 其他 CDN |

装之前三道校验：sha256 → APK 签名必须与已装一致 → versionCode 必须更大。
所以即使传输层被中间人替了，也装不进去。

安装依次尝试两条路：

1. **relay-helper root 通道**（见下一节）：`pm install -r -d …; sleep 2; am start …` 拼在
   同一条命令里，静默安装且自动拉起。helper 是独立的 root 进程，装包杀掉本 app 后它照样
   能执行完 `am start`，这是它比「app 自己起 su」可靠的原因。
2. 系统安装器 Intent（需手动点确认）；都不行就把 APK 留在 `/sdcard/Download`，`adb install -r` 兜底。

固件 `/system/xbin/su` 只放行 uid 0/2000，uid 10075 的 smsrelay 永远提不了权，
旧的 `su` 分支（每次白等 120s）和 webkey `/run` + Digest 分支已整体删除。

**这台机器踩到的两个坑**（代码里已处理）：

- Android 4.4 的 `HttpURLConnection` 是 OkHttp 1.x，会用自己的 ConnectionSpec 覆盖
  `setSSLSocketFactory` 里设的 TLS 版本/套件，连 GitHub 恒定 `sslv3 alert handshake failure`；
  加上本机 Conscrypt 不带 AES-GCM 套件，现代站点基本握不上手。现在改为自写 Socket 客户端
  （`Net`），失败时自动降到 `/system/bin/curl`（自带 OpenSSL，TLS1.2+GCM 靠它）。
- `pm install` 会杀掉本进程，所以「装包」和「am start」必须写在同一条 root 命令里，
  否则进程先死，重启指令发不出去。

## 构建（无 Gradle，纯 JDK + build-tools）

依赖：JDK 17、`platforms;android-19`、`build-tools;34.0.0`（已装于 WSL `~/android-tools`、`~/android-sdk`）。

```sh
sh build.sh                    # 产物 dist/com.sp970.smsrelay-<ver>.apk + dist/latest.json
VER=1.4.0 VC=10400 sh build.sh  # CI 用：版本号/versionCode 从环境变量注入
sh tools/run-tests.sh  # 离线测试：通道 JSON/钉钉载荷/验证码/退避/转发规则引擎/HTTP 服务，不需要设备
```

`aapt` 会把 `assets/web/index.html` 打进包，所以改了页面必须重新构建。
`tools/run-tests.sh` 用纯 JVM 跑 `test/` 下的断言（自己下 org.json，不需要 Android SDK），
真机不在手边时先用它挡纯逻辑问题。

## 部署（设备 192.168.42.129，网络 adb）

```sh
adb connect 192.168.42.129:5555
python tools/deploy-helper.py                 # 设备侧 root helper（幂等，可重放）
adb install -r dist/com.sp970.smsrelay-<ver>.apk
adb shell am start -n com.sp970.smsrelay/.MainActivity   # 必须！解除 stopped state
adb push config/smsrelay.conf /sdcard/smsrelay.conf
```

`deploy-helper.py` 会：把 busybox 复制进 `/system/xbin`、push `tools/relay-helper/` 到
`/system/etc/relay-helper/`、在 `init.qcom.post_boot.sh` 的**两个长循环之前**幂等插入启动块、
把最终开机脚本快照写回仓库 `tools/relay-helper/init.qcom.post_boot.sh`、立即拉起并校验。
加 `--reboot` 会热重启一次验证开机自启；`--verify-only` 只校验不改设备。
主机上有两个版本的 adb 时统一用 `-L tcp:5039`（与 `diag/adblib.py` 一致），避免互杀 server。

`MainActivity.onCreate` 会顺手 `startService`，所以一句 `am start` 就能把常驻服务和面板带起来
（早期版本要另外 `am startservice -n com.sp970.smsrelay/.RelayService`）。

签名注意：`debug.keystore` 不入库，换机器重建会得到不同证书，`install -r` 会报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先 `adb uninstall`（会清掉 `relay.db` 历史，
`/sdcard/smsrelay.conf` 不受影响）。

### 装完自检

```sh
sh tools/smoke-test.sh 192.168.42.129:8080 admin:admin
# 想顺带验真发短信，再加一个收件号码
sh tools/smoke-test.sh 192.168.42.129:8080 admin:admin 10086
```

脚本会逐项打面板接口（无凭据 401、首页、status/sys/messages/outbox/config/logs/wifi、
注入测试消息、补收），再打维护通道（uid=0、health、体检、命令框、日志、文件、crontab、
日志打包、备份、LED），再打移动网络（radio 状态/APN 列表/流量/SIM 锁/attach 诊断/
ping/wifi 高级/APN 增删演练），共 30 项，最后汇总通过/失败项。重启/关机/制式写入/
数据开关/首选 APN 切换/恢复出厂不进自检，需人工在面板二次确认。

## 真机验证记录（SP970-B / 4.4.4 / 2026-11-06）

`tools/smoke-test.sh` 11 项全通过，外加以下人工确认：

| 项 | 结果 |
|---|---|
| 收信链路 | 面板发 `105` 给 10086 → 10086 真实回复 → `SMS_RECEIVED` 入库 → 钉钉送达（status=1） |
| 发信链路 | `/api/send` 到 10086，`out.status=1`，`parts=1`，无错误 |
| 补收去重 | 连续 3 次 `/api/inbox/import`，events 不变、新增 0 条 |
| 配置写回 | POST 原样回写 → `/sdcard/smsrelay.conf` 留下注释头 + 合法 JSON，中文与 `\n` 无损 |
| 配置校验 | `maxTries=999` / `type=ftp` / 非 http url 三种非法输入均被拒，且不污染已存配置 |
| 面板换端口 | 8080→8081 返回 `restarted:true`，8081 可访问、8080 关闭；改回 8080 同样生效 |
| 信号读数 | LTE 下 `rsrp=-97 / rsrq=-14 / level=2 / asu=43 / tech=gsm\|lte`，与 `dumpsys telephony.registry` 的 `SignalStrength: 99 0 -120 -160 -120 -1 -1 25 -97 -14 2 ... gsm\|lte` 逐字段对得上 |
| WiFi 硬件 | 有 `wlan0`（MAC `08:97:58:1a:af:da`）；WiFi 关闭时模块不加载、网卡消失，属 4.4 按需 insmod 的正常行为 |
| 热点启停 | `ap_on` → `ap=true`，wlan0 拿到 `192.168.0.1/24`，hostapd 日志 `Using interface wlan0 ... ssid "ufi_56423"`；`ap_off` 干净关闭 |
| 热点改配置 | `ap_set` 改 SSID/密码后 softap.conf 与 hostapd.conf 同步更新，hostapd 以新 SSID 重启；测完已恢复原值 |
| 热点客户端 | 按网卡过滤而非网段（热点是 `192.168.0.x`，RNDIS 是 `192.168.42.x`），ARP 里只保留 `0x2` 已解析条目、排除热点自身地址 |
| 扫频 | station 开启时扫到 4 个 AP（含中文 SSID）；热点开着时返回明确 `hint` 而不是空列表 |

真机暴露并已修掉的问题：

1. **信号恒为「未知」**：4.4 上 `SignalStrength.getGsmSignalStrength()` 在纯 LTE 下返回 99，
   原实现据此判成无信号。改为解析 `SignalStrength.toString()` 的字段（含末尾的 `gsm|lte` 制式标记），
   优先取 LTE `rsrp`，并额外暴露 `rsrp/rsrq/rssnr/lteSs/tech`。
2. **补收会重复转发 + `pending` 卡住**：去重键原来是 `MD5(ts|peer|body)`，广播的短信中心时间戳
   与收件箱 `date` 差 2 秒就会被当成新短信；同时历史遗留的孤儿 `dl` 记录（`ev_id` 指向已不存在的事件）
   被 `due()` 的 INNER JOIN 跳过，永远停在 `status=0`。现在改为「号码+内容+90s 窗口」判重，
   并在服务启动时用 `purgeOrphans()` 清掉孤儿投递。
3. **热点开关根本没通过**：反射 `m("setWifiApEnabled")` 没带参数类型，取到的方法签名是空参，
   一调就是 `NoSuchMethodException`（v1.1 里就已存在，只是没有真机验证过）。
   必须 `getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class)`。
4. **热点客户端列表永远是空**：原来按写死的 `192.168.42.` 网段过滤，而这台机器的热点网段是
   `192.168.0.0/24`。改为按网卡过滤（`wlan*/ap*/p2p*`，排除 `rndis*/rmnet*/usb*`）、
   排除热点自身地址、只保留 ARP `0x2` 已解析条目。
5. **热点开着时改不了 SSID**：框架会忽略对已开启热点的重复 `enable`，必须先关一次再带新配置开。
6. **station 与热点的地址混用**：`station.ip` 原来会用 wlan0 的地址兜底，热点模式下就显示成
   `192.168.0.1`，看起来像「连着 192.168.0.1」。现在 `station.ip` 只放连路由器拿到的地址。

## 调试通道（无屏利器）

```sh
# 注入一条测试消息（走完整入库→投递链路）
adb shell am broadcast -a com.sp970.smsrelay.TEST --es body '测试' --es peer 10086

# 真发短信（可测 10086 自动回复来验证收信链路）
adb shell am broadcast -a com.sp970.smsrelay.TEST --es sendto 10086 --es body '1'

# 补收：把系统收件箱里漏转发的短信全部补进队列
adb shell am broadcast -a com.sp970.smsrelay.TEST --ez importinbox true
```

日志：`adb logcat -s SmsRelay`。数据库：`/data/data/com.sp970.smsrelay/databases/relay.db`
（导出须先 `am force-stop`，PERSIST journal 模式运行中导出会撞未检查点事务）。

## 设备侧基础设施（非本仓库职责，备查）

- 网络 adb 持久化：`/system/build.prop`（`rndis,adb` + tcp.port）+ `/system/etc/init.qcom.post_boot.sh`
  里的 adbd 看门狗（40×15s）与 smsrelay 开机兜底拉起（20×15s）
- root 通道：**relay-helper**（本仓库自维护）`busybox httpd` 只听 `127.0.0.1:8899`，
  CGI `cgi-bin/cmd` 以 root 执行，由 `init.qcom.post_boot.sh` 在长循环之前拉起 + 30s 看门狗。
  旧的 webkey 后台 `POST /run`（Digest）已不再依赖，可安全删除 webkey。
- 镜像工具：`tools/patch-system-img.sh`（debugfs 离线删应用/注入 APK）、`tools/remove-vendor.txt`（删除清单）

## 固件缺陷修复（tools/hwfix）

原厂固件带两个缺陷，修复脚本与完整原理说明见 [`tools/hwfix/README.md`](tools/hwfix/README.md)。

| 修复 | 现象 | 根因 | 入口 |
|---|---|---|---|
| 四核 | CPU 长期只跑 1 核（`online` 恒为 `0`） | 内核 `per_cpu(idle_threads,1..3)` 为 NULL，`cpu_up()` 返回 `-ENOMEM` | `quadcore/apply.sh`，`persist.sh` 固化 |
| USB adb | 插电脑枚举不到 ADB 设备 | `init.qcom.usb.rc:618` 的 `rndis,adb` 规则漏写 `adb` 且缺 `start adbd` | `usbadb/enable.sh` |

两者都**不写 boot 分区、不刷机、可一键回滚**，`boot.img` 与出厂备份逐字节一致。
注意本机 adbd 只服务一条传输：**USB adb 与网络 adb 不能同时使用**，切换方式见该目录 README。

> 面板「USB 模式」取内核的 `/sys/class/android_usb/android0/functions`
> （`dumpsys usb` 里的 `Kernel function list`），不读 `persist.sys.usb.config`。
> 后者是出厂默认组合名（本机 `charging,none,adb`，即 `Default Functions`），
> 而 `sys.usb.state` 会被原厂第 618 行的坏规则伪报成 `rndis,adb`，都不能代表实际模式。

## 路线图

- [x] **Web 面板**：App 内嵌 HTTP 服务（端口 8080+），浏览器直连 192.168.42.129 管理
  - 收件箱浏览 / 转发记录（ev+dl 表可视化）/ 实时状态
- [x] **短信配置**：面板上编辑 channels JSON、启停通道、设备名
- [x] **短信发送**：面板写短信 → SmsManager 发送 → 发送结果回显
- [x] **系统信息**：信号/电量/流量/存储/uptime（App 内 getprop+dumpsys 直读）
- [x] **WiFi 控制**：热点启停、SSID/密码修改、客户端列表、扫频
- [x] 钉钉 markdown 卡片消息、多设备标签（`tag`）、webhook 通道重试策略独立配置（`maxTries`）
- [x] **面板访问令牌**：`web.token` 配置后 Bearer 头 / `?access_token=` 与 Basic 并存（恒时比较），
  面板一键生成随机令牌；顺带支持 `web.cors` 跨域白名单与 OPTIONS 预检——外部系统可只用
  令牌调全部 API（含对外发信）
- [x] **概览 SSE 实时化**：`/api/events` 变更即推（≤1s），无变更 15s 心跳，30 分钟强制重连，
  连接上限 3；新短信（queue.events 增长）联动刷新短信页；root 采集类接口（sys/logs）
  在 SSE 激活时降频 60s 补拉；面板 `EventSource` 断线自动降级回轮询（MQTT 需要外部 broker，不采用）
- [x] **DDNS 动态域名**：station 报 NAT 出口 IPv4、IPv6 直报全球单播地址（蜂窝 CGNAT 不误报）；
  可指定 IPv6 网卡（`iface`）与推送类型（`family`：v4/v6/both）；面板状态行实时显示当前 IPv6；
  DuckDNS / Cloudflare 双服务商，变更才更新 + 失败接告警推送
- [x] **短信规则引擎**：规则数组按序求值（号码/正文正则、时段、优先级、stop、模板覆盖），
  `drop` 入库不转发、未命中按 `rulesDefault` 兜底；`dl.prio` 让验证码在投递循环里插队；
  面板规则卡片 + `/api/rules/validate|test`（详见「转发规则」一节）
- [x] **维护通道（B0 + B1）**：开机自启的 root helper + 静默升级 + 命令框 + 电源管理 +
  日志打包 + 一键体检 + 健康自检 + 备份恢复 + 文件下载 + 定时任务 + LED 定位
- [x] **B2 换卡救砖**：APN 增删改/首选切换（content provider + `svc data` 重连）、
  网络制式（写 `preferred_network_mode`，保留原厂后缀，重启生效）、移动数据/漫游、
  SIM 锁（ICCID 白名单，log/block 两档）、流量统计与清零、网络诊断（attach/ping/traceroute/
  dns/route/netstat/tcpdump）、恢复出厂（只重置自研配置，绝不删短信库）。
  WiFi 高级按真机能力收敛为：隐藏 SSID / 最大关联数 / 自动信道 / 踢客户端（hostapd_cli）——
  改信道、改发射功率、MAC 白名单在这台裁剪 hostapd + QCA 驱动上没有可用原语，明确不做。
  （待办：冷启动后重跑一次开机窗口验证 `verify 5 --reboot`）
- [ ] 定时任务：定时重启热点、定时补收（crontab 已可写，缺预设模板）——低电量告警已并入
  「告警推送」（`alerts.batteryPct`）

### B2 落地补充（2026-11-06 深夜真机验证）

- **APN**：读走 `sqlite3 -separator`，写走 `content insert/update/delete`（provider 发变更
  通知，不用重启 phone）；首选切换是 `content update --uri .../preferapn --bind apn_id:i:<id>`
  + `svc data disable/enable` 重连。真机演练：新增 ZZ_SMOKE → 删除，首选 1602↔1604 往返。
- **ICCID 带 f 填充位**：这台 ROM 读出的 ICCID 是 `898600682226f0488598`（BCD 填充），
  比较和入库前统一只保留数字，否则白名单永远匹配不上。
- **SIM 锁**：白名单整串或末 4 位都认；`log` 只记录，`block` 停止转发（消息仍入库，
  换回原卡可补发）。服务循环每轮检查。
- **helper 开机自启的坑（本次抓到）**：`/data` 跨开机不清理，上次开机的 pidfile 到新开机
  里 PID 几乎必被复用，只判 `/proc/<pid>` 存在会让 `start.sh` 误判「已有实例」直接退出
  （实测旧 pid 1374 变成了带 androidboot 参数的内核进程）。已改为核对
  `/proc/<pid>/cmdline` 是否真的含 `start.sh` / `httpd` / `crond`，修复后重启验证通过。
- **「热重启后 adbd 假死」其实是假象（已修正）**：真正的根源是这台 PC 同时跑了
  3 个 adb server（Windows 5037、WSL 5038、WSL 5039），热重启后三者同时抢 5555，
  adbd 先到先得，输家永远 offline；「冷启动能恢复」只是 RNDIS 断开重排了时序，
  不是冷启动治好了什么。**治本：全机只跑一个 adb server（本项目统一 WSL `-L tcp:5039`）；
  应急：先 kill 掉其他 adb server 再连，热重启后等 30–60 秒再 connect。**
  当时在假死状态下用面板 8080 + relay-helper 完成 1.5.0 的「无 adb 部署」
  （APK 分块 base64 经 `/api/maint/cmd` 写入 → sha256 校验 → `pm install -r` + 自动拉起）
  —— 这条救援通道的价值因此得到实证，保留此记录。
- **APN 演练用的 ZZ_SMOKE** 已删；factoryReset 演练后配置已从备份恢复并写回
  `/sdcard/smsrelay.conf`（面板保存时若 conf 不存在则只写 prefs，部署/迁移以 conf 为准）。

## 目录结构

```
msm8916-box/
├── AndroidManifest.xml        # API 19，短信/WiFi/电话状态权限
├── build.sh                   # aapt→javac→d8→zipalign→apksigner 六步流水线
├── src/com/sp970/smsrelay/    # 40 个类（含 Router.java、Alerts.java、AlertLogic.java、Ddns.java、DdnsLogic.java、MobileKeepalive.java），无第三方依赖，无 res/
├── assets/web/index.html      # 面板单页（8 个顶级 tab，短信内含 3 个子页签，aapt -A assets 打包）
├── test/                      # 离线测试源码（纯 JVM，tools/run-tests.sh 驱动）
├── config/smsrelay.conf.example
└── tools/
    ├── deploy-helper.py       # 设备侧 helper 一键部署 / 校验（可重放）
    ├── relay-helper/          # 设备侧文件（CGI、开机脚本、crontab），全部版本化
    ├── smoke-test.sh          # 装完自检 30 项
    ├── run-tests.sh           # 离线测试
    ├── hwfix/                 # 固件缺陷修复（四核热补丁 / USB adb），见 tools/hwfix/README.md
    └── patch-system-img.sh    # 离线定制 system.img（debugfs）
```
