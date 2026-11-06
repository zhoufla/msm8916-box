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
| `Db` | SQLite 三表：`ev`（收信本体，号码+内容+90s 窗口去重）+ `dl`（消息×通道投递状态，退避 1/2/4/8/16/30min，上限按通道配置）+ `out`（发件记录） |
| `Sender` | 钉钉（text 或 markdown 卡片，可选 SEC 加签）/ 通用 webhook（JSON + 可选 X-Token），多设备标签，UTF-8 |
| `SmsSender` / `SendReceiver` | 面板发短信：分片发送，逐片回写结果 |
| `Inbox` | 补收：把系统收件箱里漏转发的短信补进队列 |
| `AppConfig` | 配置优先级：`/sdcard/smsrelay.conf`（JSON）> SharedPreferences > 内置示例 |
| `WebPanel` / `Http` | 内嵌 HTTP/1.1 服务（纯 JDK，无第三方库）+ JSON API + 静态页面，Basic 鉴权 |
| `SysInfo` | 型号/基带/信号（rsrp+rsrq+rssnr+制式，PhoneStateListener 缓存不额外耗电）/电量/存储/流量/uptime/网卡 |
| `WifiCtl` | 热点启停与 SSID/密码、station 开关、驱动与硬件区分、网卡 MAC/IP、ARP 客户端、扫频 |
| `WifiCtl` | 反射调用 hidden API 启停热点、改 SSID/密码；`/proc/net/arp` 读客户端；扫频 |
| `Logs` | 内存环形日志，面板「最近日志」直读 |
| `TestReceiver` | 调试通道（见下） |
| 保活 | 前台通知 + START_STICKY + AlarmManager 5min 兜底 + PARTIAL_WAKE_LOCK |

消息格式（钉钉，关键词校验友好）：

```
设备：158
来自：10086
<短信正文>
```

验证码类短信自动提取 4~8 位数字进 `code` 字段。

### 通道字段

| 字段 | 说明 |
|---|---|
| `name` | 唯一标识，投递记录按它区分 |
| `type` | `dingtalk` / `webhook` |
| `url` | 必须 http(s) 开头；为空则该通道不投递 |
| `secret` | 钉钉加签（SEC 开头），配了才带 timestamp/sign |
| `token` | webhook 的 `X-Token` 请求头 |
| `template` | 正文字模板，默认 `{peer}\n{body}\n设备：{device}`（第一行发件人、第二行内容、第三行设备名）。占位符：`{peer}` `{body}` `{device}` `{tag}` `{time}` |
| `format` | 钉钉专用：`text`（默认）/ `markdown` 卡片 |
| `title` | markdown 卡片标题，空则自动「短信 · 设备 · 号码」 |
| `tag` | 多设备标签：写进钉钉卡片标题与 webhook 载荷；要在正文里出现就用 `{tag}` 占位符 |
| `maxTries` | 该通道失败重试上限（1~50，默认 10） |
| `enabled` | 是否启用 |

## Web 面板

服务启动即拉起面板，默认 `http://192.168.42.129:8080`（8080 被占用自动顺延到 8085，
实际端口见常驻通知或 App 界面）。默认账号 `admin / admin`，在配置的 `web` 段里改：

```json
"web": {"enabled": true, "port": 8080, "user": "admin", "pass": "admin"}
```

六个页面：**概览**（队列/设备/信号/最近日志）· **短信**（收信 + 每通道投递状态 + 重发 +
补收 + 注入测试）· **发送**（发短信 + 发送记录）· **通道**（通道增删改、设备名、面板账号）·
**系统**（硬件/系统/存储/流量/网卡）· **WiFi**（热点开关与 SSID/密码、station 开关、
无线网卡与 MAC/IP、客户端、扫频）。

页面是单文件、无外链 CDN，设备没网也能打开；XHR + ES5，老浏览器也能用。

### API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 概览：设备、面板、通道、队列计数 |
| GET | `/api/sys` | 系统信息全量 |
| GET | `/api/messages?limit=50` | 收信列表 + 每通道投递状态 |
| POST | `/api/messages/retry` | 重发（`{"id":123}`），顺带补建新通道的投递 |
| POST | `/api/messages/test` | 注入测试消息（`{"peer","body"}`） |
| POST | `/api/inbox/import` | 补收系统收件箱 |
| POST | `/api/messages/delete` | 删收信：`{"id":123}` 单条（连投递一起删），`{"scope":"givenup"}` 清全部「已放弃」投递及因此变空的消息 |
| POST | `/api/send` | 发短信（`{"to","body"}`） |
| GET | `/api/outbox?limit=20` | 发件记录 |
| POST | `/api/outbox/delete` | 删发件记录（`{"id":3}`，只删记录不动已发的短信） |
| GET | `/api/config` | 当前生效配置 + 来源文件 + 面板状态 |
| POST | `/api/config` | 保存配置（body 为完整配置 JSON） |
| GET | `/api/wifi` | 热点 + station + 网卡 + 客户端 + `hint` |
| POST | `/api/wifi` | `{"action":"ap_on\|ap_off\|ap_set\|sta_on\|sta_off","ssid","pass"}`（`on/off/set` 兼容旧面板），回 `{ok,message,state}` |
| GET | `/api/wifi/scan` | 扫频，回 `{ok,list,hint}`（station 开启时才有结果） |
| GET | `/api/logs?limit=80` | 内存日志 |

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
curl -u admin:admin -X POST -d '{"to":"10086","body":"1"}' http://192.168.42.129:8080/api/send
```

## 构建（无 Gradle，纯 JDK + build-tools）

依赖：JDK 17、`platforms;android-19`、`build-tools;34.0.0`（已装于 WSL `~/android-tools`、`~/android-sdk`）。

```sh
sh build.sh          # 产物 dist/com.sp970.smsrelay-1.1.apk（约 57KB，自签名）
sh tools/run-tests.sh  # 离线测试：通道 JSON/钉钉载荷/验证码/退避/HTTP 服务，不需要设备
```

`aapt` 会把 `assets/web/index.html` 打进包，所以改了页面必须重新构建。
`tools/run-tests.sh` 用纯 JVM 跑 `test/` 下的断言（自己下 org.json，不需要 Android SDK），
真机不在手边时先用它挡纯逻辑问题。

## 部署（设备 192.168.42.129，网络 adb）

```sh
adb connect 192.168.42.129:5555
adb install -r dist/com.sp970.smsrelay-1.1.apk
adb shell am start -n com.sp970.smsrelay/.MainActivity   # 必须！解除 stopped state
adb push config/smsrelay.conf /sdcard/smsrelay.conf
```

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
注入测试消息、补收），最后汇总通过/失败项。

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
- root 救援通道：webkey 后台 `POST http://192.168.42.129/run`（Digest admin/admin，uid=0）
- 镜像工具：`tools/patch-system-img.sh`（debugfs 离线删应用/注入 APK）、`tools/remove-vendor.txt`（删除清单）

## 路线图

- [x] **Web 面板**：App 内嵌 HTTP 服务（端口 8080+），浏览器直连 192.168.42.129 管理
  - 收件箱浏览 / 转发记录（ev+dl 表可视化）/ 实时状态
- [x] **短信配置**：面板上编辑 channels JSON、启停通道、设备名
- [x] **短信发送**：面板写短信 → SmsManager 发送 → 发送结果回显
- [x] **系统信息**：信号/电量/流量/存储/uptime（App 内 getprop+dumpsys 直读）
- [x] **WiFi 控制**：热点启停、SSID/密码修改、客户端列表、扫频
- [x] 钉钉 markdown 卡片消息、多设备标签（`tag`）、webhook 通道重试策略独立配置（`maxTries`）
- [ ] 面板访问令牌（免 Basic 弹窗，方便嵌到其他系统）
- [ ] 短信规则：关键字/号码白名单过滤，验证码单独走低延迟通道
- [ ] 定时任务：定时重启热点、定时补收、低电量告警

## 目录结构

```
msm8916-box/
├── AndroidManifest.xml        # API 19，短信/WiFi/电话状态权限
├── build.sh                   # aapt→javac→d8→zipalign→apksigner 六步流水线
├── src/com/sp970/smsrelay/    # 21 个类，无第三方依赖，无 res/
├── assets/web/index.html      # 面板单页（aapt -A assets 打包）
├── test/                      # 离线测试源码（纯 JVM，tools/run-tests.sh 驱动）
├── config/smsrelay.conf.example
└── tools/                     # run-tests.sh 离线测试、smoke-test.sh 装完自检、patch-*.sh 镜像定制
```
