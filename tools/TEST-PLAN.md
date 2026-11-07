# msm8916-box 完整功能测试计划

> 基线：v1.8.0（versionCode 10800）· 五期功能+六项体验+验证码模板+DDNS 多记录构建 `dist/com.sp970.smsrelay-1.8.0.apk`
> 设备 SP970-B @ `192.168.10.196:8080`（admin:admin）
> 编制：2026-11-07　状态标记：☑ 已通过　☐ 待测　➖ 不适用/可选

## 0. 测试分层与现状

| 层 | 内容 | 工具/方式 | 状态 |
|---|---|---|---|
| L0 | 桌面单元测试（纯 JDK：Router/AlertLogic/DdnsLogic/Sender/Tpl/Http…） | `tools/run-tests.sh` | ☑ **224/0**（+Wifi 26 +CpuFreq 21 +Http 8 步） |
| L1 | 真机 API 全量回归（只读断言+可恢复写操作） | `tools/panel-e2e.sh 192.168.10.196:8080 admin:admin` | ☑ **72/72** |
| L2 | 五期新功能 API 专项 | `G:\dsh-data\dsh-patches\device_newfeat2/3.py` | ☑ **15/15** |
| L3 | 端到端真实场景（真实短信/真实推送/真实解析） | 手工，本文重点 | ☐ |
| L4 | 稳定性与异常恢复 | 手工+观察 | ☐ |
| L5 | 破坏性专项（可选，谨慎） | `tools/smoke-test.sh` 等 | ☐ |

**本轮 L3–L5 目标：把"API 正确"升级为"真实链路正确、长期运行正确"。**

## 1. 环境准备

- [ ] **P-01** 第二台手机（可发真实短信），记下其号码
- [ ] **P-02** 钉钉群机器人在线（当前唯一启用通道，会产生真实推送——提前告知群成员测试期会有消息）
- [ ] **P-03** DuckDNS 免费域名 + token（DDNS 用）；可选 Cloudflare zone + API Token
- [ ] **P-04** 基线备份（PC 端留存）：`curl -u admin:admin http://192.168.10.196:8080/api/maint/backup -o backup.bin` + `/api/config` 存 json + `adb`/面板备份旧 APK 意识（回滚方案见 §7）
- [ ] **P-05** 记录基线数据：`/api/status` 队列计数、`dumpsys meminfo com.sp970.smsrelay` PSS、`/data/data/com.sp970.smsrelay/databases` 记录数（经 `/api/messages` total）
- [ ] **P-06** 设备时间已同步（涉及时段规则/提醒窗口的测试前确认）

## 2. L3 — 端到端场景用例

### 2.1 规则引擎 RULE（前置：面板 → 短信 → 转发配置 → 规则卡片）

前置规则（除非用例另述）：
```json
[
  {"name":"otp","peer":["^1[3-9]\\d{9}$"],"body":["验证码|code"],
   "channels":["钉钉通道名"],"priority":100,"stop":true},
  {"name":"drop-ads","body":["退订"],"action":"drop"}
]
```

| ID | 步骤 | 预期 | 状态 |
|---|---|---|---|
| RULE-01 | 第二台手机发"您的验证码 4821，10 分钟内有效" | 钉钉收到；面板该记录显示命中规则 `otp →`，deliveries 仅目标通道 prio=100 | ☐ |
| RULE-02 | 发普通聊天短信（不含关键字） | 所有启用通道均收到；无规则标记（unmatched=all 兜底） | ☐ |
| RULE-03 | 发含"退订回T"的短信（可自编） | 钉钉**不**推送；面板列表出现"已丢弃 · 规则 drop-ads"，记录仍可查 | ☐ |
| RULE-04 | 临时加规则 b（同 peer、prio=10、无 stop、另一通道）再发验证码短信 | 仅 otp 命中（stop 截断）；去掉 stop 后重发 → 两通道都收到、面板 targets 两行 | ☐ |
| RULE-05 | 进阶：把钉钉 url 改坏→注入"验证码 111"（prio100）与"普通消息"（prio0）→ 攒队失败→改回 url→wake | 恢复后先投 prio100 再投 prio0（日志顺序可证 due 排序） | ☐ |
| RULE-06 | 时段规则（可选）：root `date -s` 设时刻在/不在窗口内各测一次；含跨零点窗口 23:00–04:00 | 窗口内命中、窗外不命中；**测完恢复自动对时** | ➖ 可选 |
| RULE-07 | 规则 template："验证码 {code} 来自 {peer}"（钉钉通道） | 钉钉内容按模板渲染（注意：模板覆盖仅钉钉分支生效，webhook 分支走事件 JSON，如实记录） | ☐ |
| RULE-08 | 面板 UI：校验按钮（好/坏 JSON 各一次）→ 匹配测试器输入号码+正文 | 好规则 ✓、坏规则具体报错；测试器显示命中链/目标/丢弃 | ☐ |
| RULE-09 | 保存规则 → 重启设备 → 面板读回 | 规则原样持久化并继续生效 | ☐ |
| RULE-10 | 人工动作：面板对已送达记录点"重发"；系统收件箱点"转发" | 两者均走全部通道（人工显式意图不走规则，行为与文档一致） | ☐ |
| RULE-11 | 防御：面板保存含坏正则/不存在通道的规则 → 400 不落盘；再直接改 conf 文件写坏规则 → 重启服务 | 面板路径拒绝；文件路径宽松跳坏条目、服务不崩 | ☐ |
| RULE-12 | 验证码延迟秒表：RULE-01 同时计时（发出→钉钉响） | ≤5s（新链路 route 后立即 wake，不再受 5–30s 轮询影响）；记录实际值 | ☐ |

### 2.2 访问令牌 TOKEN + 跨域 CORS（前置：维护 → 设备与面板）

| ID | 步骤 | 预期 | 状态 |
|---|---|---|---|
| TOKEN-01 | 默认无 token：Basic 200 / Bearer 401 | 与今日基线一致 | ☑ |
| TOKEN-02 | 配置 token（面板生成按钮）→ curl Bearer 抽查 `/api/status` `/api/config` `/api/messages` `/api/send` | 全部 200；错误 Bearer 401 | ☑（send 除外→TOKEN-08） |
| TOKEN-03 | 清空 token → Basic 200 / Bearer 401 | 与今日基线一致 | ☑ |
| TOKEN-04 | SSE `?access_token=` | 200 且推流 | ☑ |
| TOKEN-05 | 输入 200 字符 token 保存 → 读回 | 截断为 128 字符 | ☐ |
| TOKEN-06 | token 已配时浏览器旧标签页操作 | 401 后重新登录（Basic）恢复；带 token 新页正常 | ☐ |
| TOKEN-08 | **计费项**：`curl -H "Authorization: Bearer …" -d '{"to":"第二台号码","body":"API 发信测试"}' /api/send` | 第二台手机收到短信；面板发件箱有记录 | ☐ |
| CORS-01 | 默认未配置：`curl -sI -H "Origin: http://a.local:8123" /api/status` | 响应无任何 CORS 头（原行为不变） | ☐ |
| CORS-02 | 配 `web.cors=["http://a.local:8123"]` → 同上 | `ACAO` 回显该 origin + `ACAM/ACAH/Max-Age` | ☐ |
| CORS-03 | 换不在白名单的 Origin | 无 ACAO | ☐ |
| CORS-04 | `web.cors=["*"]` | 任意 Origin 均放行 | ☐ |
| CORS-05 | `curl -X OPTIONS -H "Origin: …"`（不带 Authorization） | 204 预检通过 | ☐ |
| CORS-06 | 浏览器实测：本地静态页 `fetch('http://192.168.10.196:8080/api/status')`（白名单内/外各一次） | 白名单内成功；外被浏览器拦截 | ☐ |

### 2.3 告警推送 ALERT（前置：面板 → 维护 → 告警推送卡；⚠️ 会真实推钉钉）

| ID | 步骤 | 预期 | 状态 |
|---|---|---|---|
| ALERT-01 | 默认关闭+配置持久化（规范化回存） | enabled=false；保存读回一致 | ☑ |
| ALERT-02 | 健康翻转（可选观察项）：alertIntervalMin=1，kill helper 的 httpd | helper 由 30s 看门狗自动拉起，60s 评估窗可能错过翻转——错过属预期，捕获到则应收到"健康异常"与"恢复"两条推送 | ➖ 可选 |
| ALERT-03 | batteryPct=99 → 等 alertIntervalMin 周期 | 收到"电量低于 99%"推送（dumpsys battery 真实电量 < 99）；恢复 0 后不再推 | ☐ |
| ALERT-04 | trafficGb=低于 `/api/radio/traffic` 现值（注意校验范围，必要时先用 1GB 档） | 收到流量超额推送；恢复 0 后不再推 | ☐ |
| ALERT-05 | 断网事件：开启飞行模式（或切断上行）≥1min → 关闭 | 收到 keepalive"重建 HIPRI/失败"推送与"链路已恢复"推送；期间队列积压，恢复后补投 | ☐ |
| ALERT-06 | 持续异常只推一次 | ALERT-03 期间连续两个周期观察：第二周期无新推送（remindH 默认 6h） | ☐ |
| ALERT-07 | alerts.channels 指定单通道 | 推送只发该通道 | ☐ |
| ALERT-08 | 推送后查 `/api/messages` total | 不增加（告警不走转发队列） | ☐ |

### 2.4 概览 SSE（前置：浏览器打开面板概览页）

| ID | 步骤 | 预期 | 状态 |
|---|---|---|---|
| SSE-01 | `curl -s -m 6 -N -u admin:admin …/api/events` | text/event-stream、无 Content-Length、retry:3000、首帧 status | ☑ |
| SSE-02 | 开 curl 监听 → 另一标签页注入测试消息 | ≤2s 内收到新 event: status（queue.events+1） | ☐ |
| SSE-03 | `curl -s -m 20 -N …` 静置 | ~15s 出现 `: hb` 心跳 | ☐ |
| SSE-04 | 4 个并发 curl -N | 第 4 个 503（连接上限 3） | ☐ |
| SSE-05 | （可选长测）单连接挂 30min | 收到 `event: bye` 后结束；客户端自动重连 | ➖ 可选 |
| SSE-06 | 浏览器：面板开着，注入消息后**不刷新**看概览队列数字 | 自动更新；Network 里 EventSource 活跃 | ☐ |
| SSE-07 | 切走标签页再切回（visibilitychange） | 隐藏时断开、回前台恢复并立即拉一次全量 | ☐ |
| SSE-08 | 配 token 时 `?access_token=` 连接 | 200 正常推流 | ☑ |

### 2.5 DDNS（前置：P-03；仅配置态测试不产生计费）

| ID | 步骤 | 预期 | 状态 |
|---|---|---|---|
| DDNS-01 | duckdns：domain=xxx.duckdns.org + token → 面板"立即更新" | `/api/ddns` lastV4=station 出口 IP；DuckDNS 控制台/`nslookup` 一致 | ☐ |
| DDNS-02 | 蜂窝 IPv6：确认 `busybox ip -6 addr show rmnet0` 有全局地址 → now | lastV6 有值；AAAA 记录更新（dig AAAA） | ➖ 视运营商 |
| DDNS-03 | 幂等：连续两次"立即更新" | 第二次 skip（ipChanged=false，日志可证） | ☐ |
| DDNS-04 | 故障：token 改错 → now | lastError 记录；若 alerts 开启则收到 ddns 告警推送；改回后恢复 | ☐ |
| DDNS-05 | cloudflare：zone+token+域名 → now | 控制台 A 记录更新（TTL 300） | ☐ |
| DDNS-06 | 周期：intervalMin=1 观察 3 个周期 | tick 按周期运行（日志"未启用，跳过"/更新行） | ☑ 接线已证 |

### 2.6 短信端到端 E2E（真实链路）

| ID | 步骤 | 预期 | 状态 |
|---|---|---|---|
| E2E-01 | 真实短信→钉钉全链路（含 RULE-12 计时） | 内容完整、通道送达标记 1；延迟记录 | ☐ |
| E2E-02 | 深睡延迟：屏幕关闭静置 10min → 发短信 | 送达（蜂窝寻呼唤醒可能额外延迟，记录基线值） | ☐ |
| E2E-03 | 长短信：发 >70 汉字 | 拼接完整、钉钉一条不截断 | ☐ |
| E2E-04 | 断网积压补投：断网时发 2 条 → 恢复网络 | 全部补投、失败重试退避可见（dl tries/next_at） | ☐ |
| E2E-05 | 判重：90s 内同号码同内容发两条 | 只投一条（面板一条记录） | ☐ |
| E2E-06 | 补收： inbox_import（或重启后自然补收） | 不重复投递（nearDuplicateId 判重） | ☐ |

## 3. L4 — 稳定性与异常

| ID | 步骤 | 预期 | 状态 |
|---|---|---|---|
| STAB-01 | `reboot` → 2min 内探测 | 服务自启（前台通知）、面板恢复、keepalive/告警/ddns tick 重启、历史记录不丢 | ☐ |
| STAB-02 | 断电→上电 | 同 STAB-01 + conf/DB 完整（记录数与断电前一致） | ☐ |
| STAB-03 | 飞行模式 5min 往返 | keepalive 重建链路、告警联动、积压补投 | ☐ |
| STAB-04 | helper 看门狗：kill helper 的 httpd | ≤40s 自动拉起，`/api/maint/helper` 恢复 alive | ☐ |
| STAB-05 | 24h 挂机观察（每日一次采样×3 天更佳） | PSS 无持续增长（<基线+20%）、queue.failed=0、SSE 连接数回落 0、health.log 增长正常、无匿名进程泄漏 | ☐ |
| STAB-06 | 老库迁移 VER=3→4 | 升级已自然发生，历史记录可读、重发可用 | ☑ |
| STAB-07 | 升级检查不自降级 | manifest 1.3.2 < 本机 10700 → "已最新" | ☑ |

## 4. L5 — 破坏性专项（可选，先备份再动）

| ID | 内容 | 备注 | 状态 |
|---|---|---|---|
| DEST-01 | `tools/smoke-test.sh` 全量（APN 增删改、WiFi 参数改动等） | 完成后按脚本提示还原 APN | ☐ |
| DEST-02 | 面板"恢复出厂" → 全量重配流程 | 验证从零部署文档可用性 | ☐ |
| DEST-03 | 真实 OTA：发布 manifest 新版本 → 面板升级按钮 → helper 静默安装 | 以后真发版时顺带验证 | ➖ 暂缓 |

## 5. 验收标准

1. L0–L2 全过（已达成：198+71+15）。
2. **必测项**全过：RULE-01/02/03/08/12、TOKEN-02/08、CORS-02/05、ALERT-03/05/08、SSE-02/06、DDNS-01、E2E-01/03/04。
3. 验证码类真实短信 → 钉钉延迟 **≤5s**（RULE-12/E2E-01），普通短信 ≤35s。
4. STAB-01/02 通过；STAB-05 观察期无异常趋势。
5. 缺陷清单为空，或遗留项均已知、可接受、有 issue 记录。

## 6. 测试后恢复清单

- [ ] `web.token` 清空、`web.cors` 清空（或按需保留并知会）
- [ ] `alerts`：enabled=false、trafficGb=0、batteryPct=0
- [ ] `ddns`：enabled=false（或按需保留）
- [ ] `rules`：保留真实使用的规则，删除测试规则（drop-ads 等）
- [ ] 系统时间恢复自动同步（做过 RULE-06 的）
- [ ] APN 与 WiFi 配置还原（做过 DEST-01 的）
- [ ] 确认 `/api/status` 队列 failed=0、活跃通道为生产通道

## 7. 回滚方案

1. 配置回滚：恢复 §1 P-04 备份的 conf（面板 → 维护 → 备份/恢复，或 `/api/config` POST 原文）。
2. APK 回滚：`pm install -r` 旧版 APK（部署管线同 `G:\dsh-data\dsh-patches\device_deploy.py`，仅换包路径）；Db 升级 VER=4 向后兼容旧版读库（新增列有 DEFAULT）。
3. helper 未动：本轮未改 helper/init 脚本，无需回滚。

## 8. 附录：命令速查

```bash
# Bearer API
curl -u admin:admin http://192.168.10.196:8080/api/status
curl -H "Authorization: Bearer <token>" http://192.168.10.196:8080/api/config

# SSE（务必 -m 限时，服务器长推不会主动断）
curl -s -m 6 -N -u admin:admin http://192.168.10.196:8080/api/events
curl -s -m 20 -i -N -u admin:admin http://192.168.10.196:8080/api/events   # 看响应头

# 规则测试 / DDNS
curl -u admin:admin -H 'Content-Type: application/json' \
  -d '{"rules":[{"name":"otp","peer":["^106\\d+$"],"body":["验证码"],"channels":["dingtalk"],"priority":100,"stop":true}],"peer":"1069012345","body":"验证码 4821"}' \
  http://192.168.10.196:8080/api/rules/test
curl -u admin:admin http://192.168.10.196:8080/api/ddns

# 设备侧（面板 cmd 通道；mksh 无 printf 内建，写盘用 busybox）
#   日志：/api/logs?limit=50   真机回归：sh tools/panel-e2e.sh 192.168.10.196:8080 admin:admin
```

### 已知实现事实（避免误报缺陷）

- 规则 `template` 覆盖仅对**钉钉分支**生效；webhook 分支发送结构化事件 JSON。
- `unmatched` 默认 `all`（向后兼容）；`drop` 语义=入库但不转发，面板可人工重发。
- 人工动作（面板重发/系统收件箱转发/注入）**不走规则**，走全通道 prio=0。
- SSE 推的是 `/api/status` 同款 JSON；`/api/sys`、`/api/logs` 在 SSE 激活时降频 30s。
- 告警状态（firing/lastSent）存进程内存，重启清零 → 重启后首个周期会重推一次在途异常。
- DDNS 蜂窝 IPv4 属 CGNAT 不上报；仅 station 模式报 v4 出口 IP，蜂窝优先报 v6。
- Update 比对 versionCode，manifest 版本更低不会自降级。
