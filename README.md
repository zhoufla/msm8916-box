# sp970box — SP970 随身 WiFi 管理套件

SP970-B（MSM8916，Android 4.4.4 eng/root）随身 WiFi 棒子的设备管理套件。
**短信网关**是第一个落地的模块：SIM 收到的短信实时转发到钉钉机器人 / 自建 webhook，
断网退避重试、开机自启。后续模块（Web 面板、短信发送、系统信息、WiFi 控制）见路线图。

> 定位：把一根只当热点用的棒子，变成可远程管理的短信网关 + 设备面板。

## 当前功能

| 模块 | 说明 |
|---|---|
| `SmsReceiver` | 监听 `SMS_RECEIVED`（优先级 999），不抢占默认短信 App，4.4 收到广播即转发 |
| `RelayService` | 常驻前台服务：扫库 → 多通道并发投递 → 标记成功/失败 |
| `Db` | SQLite 两表：`ev`（消息本体，hash 去重）+ `dl`（消息×通道投递状态，各自退避重试 1/2/4/8/16/30min，10 次放弃） |
| `Sender` | 钉钉（text + 可选 SEC 加签）/ 通用 webhook（JSON + 可选 X-Token），UTF-8 |
| `AppConfig` | 配置优先级：`/sdcard/smsrelay.conf`（JSON）> SharedPreferences > 内置示例（全关） |
| `TestReceiver` | 调试通道（见下） |
| 保活 | 前台通知 + START_STICKY + AlarmManager 5min 兜底 + PARTIAL_WAKE_LOCK |

消息格式（钉钉，关键词校验友好）：

```
设备：158
来自：10086
<短信正文>
```

验证码类短信自动提取 4~8 位数字进 `code` 字段。

## 构建（无 Gradle，纯 JDK + build-tools）

依赖：JDK 17、`platforms;android-19`、`build-tools;34.0.0`（已装于 WSL `~/android-tools`、`~/android-sdk`）。

```sh
sh build.sh          # 产物 dist/com.sp970.smsrelay-1.0.apk（约 25KB，自签名）
```

## 部署（设备 192.168.42.129，网络 adb）

```sh
adb connect 192.168.42.129:5555
adb install -r dist/com.sp970.smsrelay-1.0.apk
adb shell am start -n com.sp970.smsrelay/.MainActivity   # 必须！解除 stopped state
adb push config/smsrelay.conf /sdcard/smsrelay.conf
```

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

## 路线图（sp970box 计划中）

- [ ] **Web 面板**：App 内嵌 HTTP 服务（端口 8080+），浏览器直连 192.168.42.129 管理
  - 收件箱浏览 / 转发记录（ev+dl 表可视化）/ 实时状态
- [ ] **短信配置**：面板上编辑 channels JSON、启停通道、设备名
- [ ] **短信发送**：面板写短信 → SmsManager 发送 → 发送结果回显
- [ ] **系统信息**：信号/电量/流量/存储/uptime（SL4A 已有思路，改为 App 内 getprop+dumpsys 直读）
- [ ] **WiFi 控制**：热点启停、SSID/密码修改、客户端列表、扫频
- [ ] 钉钉 markdown 卡片消息、多设备标签、webhook 通道重试策略独立配置

## 目录结构

```
smsrelay/
├── AndroidManifest.xml        # API 19，RECEIVE/SEND_SMS、INTERNET、WAKE_LOCK
├── build.sh                   # aapt→javac→d8→zipalign→apksigner 六步流水线
├── src/com/sp970/smsrelay/    # 13 个类，无第三方依赖，无 res/（免资源编译）
├── config/smsrelay.conf.example
└── tools/                     # 镜像离线定制脚本（flash 前用）
```
