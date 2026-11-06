# 离线测试（无设备、无 Android SDK）

`tools/run-tests.sh` 会用纯 JVM 把能在桌面上跑的模块编译一遍并执行：

| 测试 | 覆盖 |
|---|---|
| `LogicTest` | 通道 JSON 往返与默认值、maxTries 夹紧、钉钉 text/markdown 载荷、tag 注入、webhook 载荷、验证码提取、失败退避曲线 |
| `HttpTest` | HTTP 服务：查询串（UTF-8）、POST JSON/表单、400/404、超长头、20 并发、stop |

`android/util/Base64.java` 是钉钉加签用到的那个 API 的替身；`Logs` 替身避免拉起
`android.util.Log`。跑真机前先跑一遍这里，能挡掉大部分纯逻辑问题。

```sh
sh tools/run-tests.sh
```
