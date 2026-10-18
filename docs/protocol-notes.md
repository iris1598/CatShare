# 小米 MiDrop「PC 模式」协议笔记

来源：`com.miui.mishare.connectivity 5.4.2`（APK 反编译）+ `MiDropTransfer.dll` /
`MiSmartShareDLL.dll` / `micont_rtm.dll`（PC 端）+ 真机实测。

## 1. 传输选择

`com.miui.mishare.ConnectionConfig`：

- `GUIDING_NETWORK_TYPE_GATT=1`、`GUIDING_NETWORK_TYPE_BLE_DIRECT_BROADCAST=2`
- `MAIN_NETWORK_TYPE_WIFI_P2P=1`、`MAIN_NETWORK_TYPE_WIFI_AP=2`
- `PROTOCOL_TYPE_MIOV_HTTP=2`、`PROTOCOL_TYPE_MI_PC_HTTP=3`

手机↔手机（互传联盟）：`(1, 1, 2)`，即 BLE GATT + Wi-Fi Direct + HTTPS/WSS（CatShare 原有实现）。
手机↔PC：`(2, 2, 3)`，即 **BLE 厂商广播（无 GATT） + Wi-Fi AP + HTTPS API 1.0**。

## 2. BLE 广播（company id 0x038F = Xiaomi Inc.）

均通过 `AdvertiseData.Builder.addManufacturerData(0x038F, payload)` 发送，
`connectable=false`、低延迟、高功率；`len` 为设备名 UTF-8 字节数。

Ability（设备可被发现，14~25 字节）：

```
[0]=len+12 [1]=0x11 [2]=0x00 [3]=0x11 [4]=0x12
[5]=(len&0xF)<<4|0x03 [6..6+len-1]=name
[len+6]=0x24 [len+7..8]=deviceId u16LE [len+9]=0x38
[len+10]=F0 [len+11]=F1 [len+12]=(random-30)&0xff
F0 = A<<3 | B<<4 | C<<5 | D<<6 | E<<7
F1 = F | G<<3 | H<<4 | I<<5 | J<<6
```

Transfer（任务广播，18+len(+3) 字节）：

```
[0]=len+0x11 [1]=0x11 [2]=0 [3]=0x11 [4]=0x02 [5]=0x24
[6..7]=selfDeviceId [8]=((len+9)<<4)|8 [9]=0x09|(support2_0?0x10:0)
[10]=fileCount(>250→0xFF) [11]=channel [12..13]=peerDeviceId
[14..15]=taskId [16..17]=nonce [18..]=name(6B)
SDK>29 时追加 [0x2F][AP IP 第3段][第4段]
```

Server-ready（手机作为 HTTPS 服务端，16 字节）：

```
[0]=0x0F [1]=0x11 [2]=0 [3]=0x11 [4]=0x12 [5]=0x24
[6..7]=selfDeviceId [8]=0x78 [9]=0x04 [10..11]=peerDeviceId
[12..13]=taskId [14]=AP IP 第3段 [15]=第4段
```

Cancel / Response（14 字节）：`[0]=0x0D [1]=0x11 [2]=0 [3]=0x11 [4]=0x12 [5]=0x24
[6..7]=deviceId [8]=0x58 [9]=subtype [10..13]=u16 字段`；
response subtype `0x13`=ok / `0x23`=fail。

扫描过滤（手机端接收 PC 广播时）：`setManufacturerData(0x038F, data, mask)`，
ability 用 `data={0F 11 00 11}`、`mask={00 FF 00 FF}`。

## 3. 热点 SSID / PSK

- `support2_0=false`：SSID = `ap_mishare_%04x`（deviceId 16 位）
- `support2_0=true`：SSID = `DIRECT-%04x`
- PSK = `Base64.DEFAULT(AES/CBC/PKCS5Padding(
    key=hex 15c8761d9e36456b9af35e1d084665a5,
    iv =hex 02050b11171f292f3b43495361676d7f,
    plaintext=[devId lo, devId hi, nonce lo, nonce hi, name[0..3] 零填充] )).substring(0,8)`
- 手机自建热点时 nonce 由手机随机生成并通过 transfer 广播带给 PC；
  PC 建热点时 nonce 来自 PC 的 transfer 广播。

## 4. HTTPS API 1.0（端口 9999，小米静态证书）

证书/私钥：RSA-2048 自签名，`C=CN,ST=Beijing,L=Beijing,O=Mi,OU=Mi`，
序列号 `7e45edbe0636d83464ef0a673a652f4afeb2fcde`，有效期 2019-07-11 → 2101-08-30，
`CA=true`，无 SAN；手机端信任库同一证书并接受任意内网 IP 主机名。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/1.0/fileinfo?user=&task=` | `{"status":0,"version":"1","data":[{name,size,id,md5,type,path}]}` |
| GET | `/api/1.0/file?user=&task=&id=` | 文件流，`content-disposition: attachment;filename=`，支持 Range |
| POST | `/api/1.0/fileverify?user=&task=` | body `{"status":0,"data":{md5,srcname,srcpath,dstname,dstpath,reason,id,code,cur,total}}`；匹配 `data.id` 的 md5；成功 `{"status":0,"data":{"reason":""}}`，失败 `{"status":9,...}` |
| POST | `/api/1.0/report?user=&task=` | 进度上报，返回 `{"status":0,"data":{"action":0}}` |
| POST | `/api/1.0/taskcomplete?user=&task=` | 完成，返回同上 |

`user` = 对端 deviceId 的十进制字符串，`task` = taskId 十进制字符串。

## 5. 状态码

`400/1` 方法错误、`400/2` 缺少参数、`404/3` user/task 不匹配、`400/4` 参数错误、
`500/5`、`404/6` 文件不存在、`500/7`、`404/8` 用户取消、`200/0` 成功、`200/9` md5 失败。
