#!/usr/bin/env pwsh
# 设默认短信 App：把 com.sp970.smsrelay 设为 4.4 的默认短信 App。
#
# 原理（4.4.4 SmsApplication.setDefaultApplication 的三件事）：
#   1. Settings.Secure "sms_default_application" = 本包名
#      → framework 下一次收 SMS 时（InboundSmsHandler 里的
#        SmsApplication.getDefaultSmsApplication(ctx, updateIfNeeded=true)）会自动：
#        · 校验本 app 的四组件（SMS_DELIVER/SENDTO/RESPOND_VIA_MESSAGE/WAP_PUSH_DELIVER）
#        · 重建 SENDTO preferred activity
#        · 把旧默认短信 App（如有）的 OP_WRITE_SMS 降为 ignored
#        · 把本 app 的 OP_WRITE_SMS 补成 allow（自愈）
#   2. JZ02 的 ROM 把 appops CLI 也精简了，所以 OP 放行完全依赖上一步框架自愈
#      （首条真实短信到来即生效）；SP970 若仍有 appops CLI，则主动 set 更稳。
#   3. OP/setting 均持久化，重启不回退。
#
# 验证：tools/jz02/provider-write-probe 的 tiny.jar checkdefault（四组件 COMPLETE）
#       + 首条真实短信后 logcat 应出现 "Delivering SMS to: com.sp970.smsrelay SmsDeliverReceiver"
#       与 "收件箱镜像成功（app 直写）"。
#
# 用法（Windows 侧，设备网络 adb 先连上）：
#   ./set-default-sms.ps1                     # JZ02（tcp:5039 / 192.168.42.129:5555）
#   ./set-default-sms.ps1 -AdbServer tcp:5037 -Serial <SP970 serial> -MmsDisable
#   ./set-default-sms.ps1 -CheckOnly
#   ./set-default-sms.ps1 -Unset              # 取消默认（恢复 root 代理直写前的状态不必）
param(
    [string]$AdbServer = 'tcp:5039',      # JZ02 走 5039 server；SP970 用 tcp:5037
    [string]$Serial = '192.168.42.129:5555',
    [switch]$MmsDisable,                    # 同时停用原装短信 App（SP970: com.android.mms）
    [switch]$MmsEnable,                     # 反悔：恢复原装短信 App（配合 -Unset 使用顺序：先 enable 再 set）
    [switch]$Unset,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$ADB = 'F:\wifi\sp970\adb\platform-tools\adb.exe'
$PKG = 'com.sp970.smsrelay'
$MMS = 'com.android.mms'

function Run([string]$cmd) {
    Write-Host "> $cmd"
    & $ADB -L $AdbServer -s $Serial shell $cmd
    if ($LASTEXITCODE -ne 0) { throw "adb shell exit ${LASTEXITCODE}: $cmd" }
}

# 容错执行（appops 等 JZ02 上被精简的命令失败不中断；框架自愈会补 OP）
function RunTolerant([string]$cmd) {
    Write-Host "> $cmd"
    $out = & $ADB -L $AdbServer -s $Serial shell $cmd 2>&1
    Write-Host "$out"
    if ("$out" -match 'not found') { Write-Host "  (跳过：此 ROM 无该 CLI，依赖框架自愈)" }
}

if ($CheckOnly) {
    Run 'settings get secure sms_default_application'
    Run "appops get $PKG WRITE_SMS"
    exit 0
}

if ($MmsEnable) {
    Run "pm enable $MMS"
    RunTolerant "appops set $PKG WRITE_SMS ignore"
    exit 0
}

if ($Unset) {
    Run "settings put secure sms_default_application ''"
    RunTolerant "appops set $PKG WRITE_SMS ignore"
    Run 'settings get secure sms_default_application'
    exit 0
}

# 正式接管
RunTolerant "appops set $PKG WRITE_SMS allow"            # 能 set 的 ROM 上先放行（不等自愈）
Run "settings put secure sms_default_application $PKG"   # 指向本 app

if ($MmsDisable) {
    Run "pm disable-user $MMS"
    RunTolerant "appops set $MMS WRITE_SMS ignore"
}

Run 'settings get secure sms_default_application'
RunTolerant "appops get $PKG WRITE_SMS"
