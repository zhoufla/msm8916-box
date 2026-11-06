# tools/hwfix — SP970 固件缺陷修复

本目录收纳 SP970（MSM8916 / Android 4.4.4 / 内核 3.10.28）上两个**原厂固件自带**的缺陷修复，
均在本机真机验证通过，都遵循同一原则：**不改 boot 分区、不刷机、可一键回滚**。

| 修复 | 现象 | 根因 | 侵入性 |
|---|---|---|---|
| [quadcore](quadcore/) | CPU 只跑 1 核，`online` 恒为 `0` | 内核 `per_cpu(idle_threads,1..3)` 为 NULL，`cpu_up()` 返回 `-ENOMEM` | `/dev/mem` 打 2 条指令补丁 |
| [usbadb](usbadb/) | USB 口插上电脑无 ADB 设备 | `init.qcom.usb.rc:618` 的 `rndis,adb` 规则漏写 `adb` | 运行时 `setprop` |

```
tools/hwfix/
├── README.md                 # 本文件
├── quadcore/
│   ├── apply.sh              # 运行时热补丁（当前这次开机生效）
│   ├── persist.sh            # 固化到 /system，开机自动生效
│   ├── rollback.sh           # 运行时回滚（本次运行恢复单核）
│   ├── unpersist.sh          # 撤销固化（恢复原厂开机脚本）
│   ├── post_boot.snippet     # 插进 init.qcom.post_boot.sh 的 hook 片段
│   └── bin/                  # 4 个机器码 payload（44B + 4B ×3）
└── usbadb/
    ├── enable.sh             # 切到正确 USB 组合 + 恢复 rndis0
    ├── disable.sh            # 切回原厂组合（恢复网络 adb）
    └── watchdog.sh           # 独立看门狗，超时自动回滚
```

两套脚本都**在设备上以 root 运行**（`adb shell` 默认 `context=u:r:shell:s0` 即已具备权限，
不需要 `su`）。设备端工具极简，脚本统一用 `busybox`（无 `sed/awk/od/free`，但有 `busybox sed`）。

---

## 一、四核修复（quadcore）

### 根因

原厂内核的 SMP 初始化有缺陷：`per_cpu(idle_threads, cpu)` 对 CPU1/2/3 是 `NULL`。
`cpu_up()` 走到：

```c
idle = idle_thread_get(cpu);      // 0xc08345ec 附近
if (IS_ERR(idle)) return PTR_ERR(idle);   // 返回 -ENOMEM (-12)
```

于是 `echo 1 > /sys/devices/system/cpu/cpu1/online` 静默失败，
`/sys/devices/system/cpu/online` 永远是 `0`。

排查时已排除：thermal / mpdecision / SELinux / cmdline / boot-DTB / CDT / 引导链 / 内存不足。
运行内核实测：`cpu_*_bits=0xf`、`nr_cpu_ids=4`、`setup_max_cpus=8` 都正常，唯独 `idle_threads` 为 NULL。

### 补丁原理

```
0xc08345ec  mvneq r4, #0xb        原指令：r4 = -ENOMEM
       ↓ 改为
0xc08345ec  beq 0xc0833500        命中条件时跳到 trampoline
```

trampoline（44 字节，放在 `0xc0833500` 的空洞区）做三件事：

1. 保存现场，调 `fork_idle()`（`0xc08338b8`）现场创建一个 idle 任务
2. 把返回值写回 `per_cpu(idle_threads, cpu)`
3. 成功则跳回 `0xc08345f4` 继续正常流程，失败跳 `0xc0834600`

落点与地址（VA / PA 换算 `PA = VA - 0x40000000`）：

| 项 | VA | `/dev/mem` word offset |
|---|---|---|
| trampoline 落点 | `0xc0833500` | `539020608`（11 word） |
| 补丁点 | `0xc08345ec` | `539021691`（1 word） |

> 踩过的坑：曾把补丁点算成 `539021675`（`0xc08345ac`），写错 64 字节，
> 现象是「读回字节正确但 CPU 依然上不了线」，一度误判为 ARM I-Cache 一致性问题。
> **地址务必以 `0xc08345ec` / word 539021691 为准。**

### 用法

```sh
# 1) 运行时打补丁（本次开机生效，掉电即失效）
adb push tools/hwfix/quadcore /data/local/tmp/hwfix-quadcore
adb shell sh /data/local/tmp/hwfix-quadcore/apply.sh

# 2) 固化到 /system（重启后自动生效）——会改 /system/etc
adb shell sh /data/local/tmp/hwfix-quadcore/persist.sh

# 3) 撤销
adb shell sh /data/local/tmp/hwfix-quadcore/rollback.sh    # 本次运行恢复
adb shell sh /data/local/tmp/hwfix-quadcore/unpersist.sh   # 撤销固化
```

`persist.sh` 的落点：

- `cp -p /system/etc/init.qcom.post_boot.sh -> 同名 .sp970bak`（备份）
- 4 个 payload → `/system/etc/sp970_hotfix/`
- `busybox sed -i "1r post_boot.snippet"` 在 shebang 后插入 hook

### 验证

```sh
# 补丁字节（应为 0afffbc3，与 bin/patch_45ec.bin 一致）
adb shell 'dd if=/dev/mem bs=4 skip=539021691 count=1 | busybox od -An -tx4'

# 负载下四核应全部上线（空闲时 mpdecision 会把 1..3 关掉，属正常省电）
adb shell '(while :; do :; done)& (while :; do :; done)& (while :; do :; done)& (while :; do :; done)& sleep 6; cat /sys/devices/system/cpu/online'
```

真机验证记录：4 路满载 6 秒 → `online=0-3`；`migration/1..3`、`ksoftirqd/1..3` 建立；
`/proc/interrupts` 出现四列；普通重启后 hook 自动重打，boot 分区 md5 与出厂一致（零写入）。

---

## 二、USB adb 修复（usbadb）

### 根因

`/init.qcom.usb.rc` 第 618–624 行：

```sh
on property:sys.usb.config=rndis,adb
    write .../idProduct 9024                  # 用 RNDIS+ADB 的 PID
    write .../functions rndis                 # ← 漏了 ,adb
    write .../enable 1                        # ← 缺 start adbd
    setprop sys.usb.state ${sys.usb.config}   # ← 却伪报成 rndis,adb
```

设备开机即 `sys.usb.config=rndis,adb`，正好命中这条坏规则，结果：

- 内核 `functions` 只有 `rndis`，FunctionFS 的 `ep1/ep2` 从未建立 → USB adb 起不来
- `sys.usb.state` 被伪报为 `rndis,adb`，`dumpsys usb` 显示 `Current Functions: rndis,adb`
  而 `Kernel function list: rndis`，框架层被自己的假状态骗过

相邻第 626 行的 `rndis,none,adb` 规则写法正确（`functions rndis,adb` + `start adbd`，PID 同为 9024）。
统计全文件 84 条组合规则、42 条名字含 adb，仅 2 条写法错误（618 行、1076 行 `charging,none,adb`），
属原厂复制粘贴笔误。

### 用法

```sh
# 启用（默认 300s 看门狗，超时自动回滚）
adb shell sh /data/local/tmp/hwfix-usbadb/enable.sh 300

# PC 侧确认可用后，落 ok 标记，阻止回滚
adb -d shell 'touch /data/local/tmp/hwfix/ok'

# 关闭（回到网络 adb）
adb shell sh /data/local/tmp/hwfix-usbadb/disable.sh
adb kill-server && adb connect 192.168.42.129:5555
```

### 三个必须知道的点

**1. 切换会重建 `rndis0`，必须重新配 IP。**
`functions` 变更后网卡被拆掉重建，不补这一条 Windows 会退成 `169.254.x.x`，网络 adb 一起失联：

```sh
busybox ifconfig rndis0 192.168.42.129 netmask 255.255.255.0 up
```

**2. 本机 adbd 只服务一条传输。**
USB adb 生效期间，TCP 5555 会 `offline`（`adb connect` 三次都失败）。
即 **USB adb 与网络 adb 不能同时用**：

- 插着 USB 线 → USB adb 优先，网络 adb 不可用
- 拔线插充电器 → USB 传输消失，网络 adb 自动恢复（仍需 `adb connect`）

**3. 后台进程必须 `setsid`。**
`adb shell 'nohup sh x.sh &'` 的子进程会被会话回收杀掉，看门狗根本不会跑；
必须 `busybox setsid sh x.sh </dev/null >/dev/null 2>&1 &`。

### 未做持久化（有意为之）

USB adb 目前只是运行时生效，重启后回到 `rndis,adb` 坏规则（USB adb 失效、网络 adb 恢复）。
如需开机自动 USB adb，可仿照 `quadcore/persist.sh` 把 `setprop` + `ifconfig` 两条加进
`init.qcom.post_boot.sh`；但那样网络 adb 会常态不可用，通常不建议。

---

## 附：为什么「改 build.prop」这条路走不通

早先仓库里有个 `tools/patch-adb.sh`（已删除），思路是把 system 镜像 build.prop 里的
`persist.sys.usb.config` 与 `ro.usb.default.config` 都改成 `rndis,adb`。这条路三重无效：

1. **写的就是坏值**。`rndis,adb` 正是 `init.qcom.usb.rc:618` 那条缺 `adb`、缺 `start adbd` 的规则，
   改完内核照样只挂 `rndis`。
2. **换到另一条坏规则**。`ro.usb.default.config` 原值 `charging,none`，改成 `rndis,adb` 只是从
   1076 行的坏规则跳到 618 行的坏规则。
3. **设备上本来就是这个值**。原厂 `build.prop:249` 就是 `persist.sys.usb.config=rndis,adb`，
   而 USB adb 从未出现过 —— 这行存在本身已是反证。

另外 Android 首次启动后会用 `/data/property/persist.sys.usb.config` 覆盖 build.prop
（本机为 `charging,none,adb`），所以对已开机的设备改 build.prop 完全不起作用。

**唯一有效的运行时做法**是 `setprop sys.usb.config rndis,none,adb`（命中 626 行正确规则）
+ `busybox ifconfig rndis0 192.168.42.129 netmask 255.255.255.0 up`。
**唯一有意义的镜像级做法**是改 boot ramdisk 里的 `init.qcom.usb.rc`（把 618 行改对），
但会破坏「boot 分区零写入」，风险最高，不建议。

## 三、通用注意事项

- **boot 分区始终零写入**：两套修复都不碰 boot/zImage/DTB，出问题最多重启恢复。
- **OTA / 恢复出厂会失效**：`/system` 被重置后 payload 和 hook 都会丢失，需要重新 `persist.sh`。
- **地址与内核强相关**：`539020608 / 539021691` 只对应本机 3.10.28 内核，换固件必须重新定位
  （用 kallsyms 找 `idle_thread_get`，再按 `per_cpu_offset` 推算）。
- **验证顺序**：先 `apply.sh` 验证运行态，再 `persist.sh` 固化；不要在没验证的情况下直接固化。
