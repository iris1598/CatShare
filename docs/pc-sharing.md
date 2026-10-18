# CatShare PC 互传（与小米电脑共享文件）

> 状态：**暂缓**。该功能已实现并通过协议级验证，但与本机 HyperConnect 2.0.2.524
> （仅 Lyra 协议）不互通，按用户要求暂时搁置。源码已移至 `shelved/pc/`（不参与编译），
> 需要恢复时移回 `app/src/main/java/moe/reimu/catshare/pc/` 并恢复清单注册与界面入口。
> 下方文档保留作为实现与协议说明。

本分支为 CatShare 增加了与小米电脑（Xiaomi HyperConnect / 小米互传）共享文件的能力，
实现的是小米「旧版 MiDrop / PC 模式」协议（`PROTOCOL_TYPE_MI_PC_HTTP`），并附带
Root 自动读取 Wi-Fi Direct MAC 地址的能力（不再依赖 Shizuku）。

## 1. 使用方式

1. 手机需要 **Root**（本机 KernelSU 已验证）。PC 互传需要创建热点，普通应用无此权限，
   因此 CatShare 通过 `su -c "cmd wifi start-softap ..."` 创建热点。
2. 在任意应用中选择文件 → 分享 → CatShare，列表顶部会出现
   **「发送到小米电脑」**（PC 互传）。点击后：
   - 创建热点 `ap_mishare_<deviceId>`（PSK 由设备号 + nonce + 设备名前 4 字节派生）；
   - 在 `:9999` 启动 HTTPS 服务（使用小米 MiDrop 内置静态证书/私钥）；
   - 通过 BLE 广播 ability / transfer / server-ready 三种厂商广播包
     （company id `0x038F`，Xiaomi Inc.）；
   - 电脑端（小米互传）发现手机后加入热点并调用 HTTPS API 1.0 拉取文件。
3. 若 BLE 扫描发现小米电脑（PC 广播 ability 包），电脑会直接显示为可发送目标。
4. **电脑 → 手机接收**：首页点击 **「从小米电脑接收文件」**，手机进入等待状态
   （广播 ability 包），电脑「小米互传」向本机发送文件时：
   - 手机扫描到 PC 的 transfer 广播后，按协议派生电脑热点 SSID/PSK 并通过 Root 加入；
   - 从 `https://<电脑热点网关>:9999` 依次调用 `fileinfo → file → fileverify → report → taskcomplete`；
   - 文件保存到 `Download/MiShare`，并在通知栏显示进度。

## 2. 实现文件

| 文件 | 说明 |
| --- | --- |
| `app/src/main/java/moe/reimu/catshare/pc/PcProtocol.kt` | MiDrop PC 模式协议常量、BLE 包构造/解析、SSID/PSK 派生（AES-CBC + Base64 前 8 位） |
| `pc/PcBle.kt` | BLE 广播（manufacturer data 0x038F）与扫描（含诊断日志） |
| `pc/PcHotspot.kt` | Root 创建/停止热点、读取 AP 地址 |
| `pc/PcRootShell.kt` | `su -c` 命令执行封装 |
| `pc/PcHttpServer.kt` | HTTPS API 1.0 服务：`/api/1.0/fileinfo`、`file`、`fileverify`、`report`、`taskcomplete` |
| `pc/PcSendService.kt` | 前台服务：热点 + HTTPS + BLE 广播 + 任务生命周期 |
| `pc/PcReceiveService.kt` | 前台服务：接收电脑文件（扫描 transfer 广播、加入电脑热点、HTTPS 客户端下载） |
| `pc/PcReceiveActivity.kt` | 接收入口（供首页按钮/调试使用） |
| `pc/PcDebugReceiver.kt` | 调试入口（可选） |
| `res/raw/mishare_cert.pem` / `mishare_key.pem` | 从小米互传 PC 组件中提取的 MiDrop 静态 RSA 证书/私钥 |
| `ShareActivity.kt` | 分享界面新增「发送到小米电脑」入口与 PC 设备扫描 |
| `AndroidManifest.xml` | 注册 `PcSendService` |

协议细节（包格式、PSK 算法、HTTP JSON schema）来自对
`com.miui.mishare.connectivity 5.4.2`（手机端）与 `MiDropTransfer.dll`
（PC 端）的逆向分析，详见 `docs/protocol-notes.md`。

## 3. 已验证内容

- 手机端完整链路（`adb` 实测）：
  - 热点创建成功：`ssid=ap_mishare_<id>`、`ap0` 获得地址、`cmd wifi start-softap` 返回成功；
  - HTTPS 服务在 `:9999` 启动，使用静态 MiDrop 证书；
  - 三种 BLE 广播均被控制器接受（ability 25B / transfer 27B / server-ready 16B）；
  - 参考 PC 客户端（`tools/leadwork/pc_ref_client.py`，按 PC 端 `MiDropTransfer.dll`
    行为实现）经 `adb forward` 访问手机服务：
    `fileinfo → file 下载 → md5 校验 → fileverify → report → taskcomplete` 全部通过，
    下载内容与 MD5 一致，会话完成后热点自动关闭。
- 接收方向（`adb` 实测）：参考 PC 服务端（`tools/leadwork/pc_ref_server.py`，
    使用同一 MiDrop 静态证书）经 `adb reverse` 提供服务，手机 `PcReceiveService`
    完成 `fileinfo → file 下载 → 保存到 Download/MiShare → fileverify → report →
    taskcomplete`，手机端文件内容与 MD5 一致，PC 服务端收到 TASK COMPLETE。
- Root MAC：`RootUtils` 读取 `p2p_supplicant.conf` 持久 P2P 设备地址，
  `ShizukuUtils` 优先使用 Root 结果；在设置页显示来源并可手动覆盖。
  实测日志见 `tools/mac-root/verification-logcat.txt`。

## 4. 已知限制

- 本机 Xiaomi HyperConnect **2.0.2.524** 的「小米互传」只使用小米 Lyra/netbus
  协议（mDNS + KCP + protobuf），其 BLE 广播被 Windows 策略禁止
  （`AdvertisingPowerPolicy: not allow`），并且不会处理旧版 MiDrop 的 `0x038F`
  BLE 广播。因此**本机这个版本的 HyperConnect 无法直接与旧版 MiDrop 协议互通**，
  实测中手机广播期间 PC 无任何 midrop 日志、未加入热点。
  该 PC 版本需要 Lyra 协议实现（工作量远大于本项目）才能互通。
- 旧版「小米互传」PC 客户端（支持 MiDrop 的版本）或实现了该协议的 PC 端程序
  （可使用本仓库提供的参考客户端/服务端思路）可与本功能互通。
- 双向（手机 → 电脑、电脑 → 手机）均已实现；接收方向的 BLE 扫描、热点加入、
  HTTPS 客户端已按协议实现，其中 HTTPS 客户端流程已用参考 PC 服务端实测通过。
