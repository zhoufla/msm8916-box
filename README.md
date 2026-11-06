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
| `Db` | SQLite 三表：`ev`（收信本体，hash 去重）+ `dl`（消息×通道投递状态，退避 1/2/4/8/16/30min）+ `out`（发件记录） |
| `Sender` | 钉钉（text + 可选 SEC 加签）/ 通用 webhook（JSON + 可选 X-Token），UTF-8 |
| `SmsSender` / `SendReceiver` | 面板发短信：分片发送，逐片回写结果 |
| `Inbox` | 补收：把系统收件箱里漏转发的短信补进队列 |
| `AppConfig` | 配置优先级：`/sdcard/smsrelay.conf`（JSON）> SharedPreferences > 内置示例 |
| `WebPanel` / `Http` | 内嵌 HTTP/1.1 服务（纯 JDK，无第三方库）+ JSON API + 静态页面，Basic 鉴权 |
| `SysInfo` | 型号/基带/信号/电量/存储/流量/uptime/网卡（PhoneStateListener 缓存信号，不额外耗电） |
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

## Web 面板

服务启动即拉起面板，默认 `http://192.168.42.129:8080`（8080 被占用自动顺延到 8085，
实际端口见常驻通知或 App 界面）。默认账号 `admin / admin`，在配置的 `web` 段里改：

```json
"web": {"enabled": true, "port": 8080, "user": "admin", "pass": "admin"}
```

六个页面：**概览**（队列/设备/信号/最近日志）· **短信**（收信 + 每通道投递状态 + 重发 +
补收 + 注入测试）· **发送**（发短信 + 发送记录）· **通道**（通道增删改、设备名、面板账号）·
**系统**（硬件/系统/存储/流量/网卡）· **WiFi**（热点开关、SSID 密码、客户端、扫频）。

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
| POST | `/api/send` | 发短信（`{"to","body"}`） |
| GET | `/api/outbox?limit=20` | 发件记录 |
| GET | `/api/config` | 当前生效配置 + 来源文件 + 面板状态 |
| POST | `/api/config` | 保存配置（body 为完整配置 JSON） |
| GET | `/api/wifi` | 热点状态 + 客户端列表 |
| POST | `/api/wifi` | `{"action":"on\|off\|set","ssid","pass"}` |
| GET | `/api/wifi/scan` | 扫频（station 模式开启时才有结果） |
| GET | `/api/logs?limit=80` | 内存日志 |

curl 示例：

```sh
curl -u admin:admin http://192.168.42.129:8080/api/status
curl -u admin:admin -X POST -d '{"to":"10086","body":"1"}' http://192.168.42.129:8080/api/send
```

## 构建（无 Gradle，纯 JDK + build-tools）

依赖：JDK 17、`platforms;android-19`、`build-tools;34.0.0`（已装于 WSL `~/android-tools`、`~/android-sdk`）。

```sh
sh build.sh          # 产物 dist/com.sp970.smsrelay-1.1.apk（约 57KB，自签名）
```

`aapt` 会把 `assets/web/index.html` 打进包，所以改了页面必须重新构建。

## 部署（设备 192.168.42.129，网络 adb）

```sh
adb connect 192.168.42.129:5555
adb install -r dist/com.sp970.smsrelay-1.1.apk
adb shell am start -n com.sp970.smsrelay/.MainActivity   # 必须！解除 stopped state
adb push config/smsrelay.conf /sdcard/smsrelay.conf
```

签名注意：`debug.keystore` 不入库，换机器重建会得到不同证书，`install -r` 会报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先 `adb uninstall`（会清掉 `relay.db` 历史，
`/sdcard/smsrelay.conf` 不受影响）。

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
- [ ] 钉钉 markdown 卡片消息、多设备标签、webhook 通道重试策略独立配置
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
├── config/smsrelay.conf.example
└── tools/                     # 镜像离线定制脚本（flash 前用）
```
