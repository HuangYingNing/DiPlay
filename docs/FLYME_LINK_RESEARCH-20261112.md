# Flyme Link 与星愿车机连接调研

## 本次 PasteDB 日志

已将服务端原始响应及日志正文保存在本机忽略目录 `.private/diagnostics/`。本次记录对应 `v0.2.22-b261112-007`、源码提交 `011586f42134`，运行环境为吉利星愿车机 `GeelyCloud E22H`、Android 11/API 30。

- Android 蓝牙适配器报告 `OFF`，已配对设备、已连接设备和 ACL 连接事件均为零；耳机和 A2DP profile 查询不受当前车机支持。
- 车机热点实际候选网卡为 `eth0`，但 Android 的热点归属接口没有确认它是 AP；接口被分类为 Flyme Wi-Fi 上游。日志中的“接口已确认”只表示网卡和地址稳定，不代表 iPhone 已连接到该 AP。
- mDNS 监听加入组播组后收到 13 个数据包，13 个均来自本机，iPhone 来源的数据包为零；没有观察到来自 iPhone 的 CarPlay/AirPlay 服务记录。
- “允许 ADB”先确认 `WRITE_SECURE_SETTINGS` 未授权、`ADB_ENABLED=0`，向 `127.0.0.1:5555` 发起授权后等待 60 秒，结果仍为 `NOT_APPROVED`。端口开放只证明有 TCP 服务监听，不能证明它是可交互的系统 ADB，更不能证明系统授权弹窗已显示。

这些证据与车内蓝牙/热点界面显示已连接并不矛盾：系统 UI 可以由厂商服务管理，而普通 Android 应用看到的是另一个受限接口。当前 DiPlay 尚未得到车机端 Bluetooth Binder 服务、iPhone RFCOMM 目标或 iPhone 发出的 Wi-Fi 组播数据，因而不能开始 iAP2 身份认证和后续 CarPlay 会话。

## Flyme Link 是否能作为入口

魅族公开说明将 Flyme Link 定位为 Flyme 手机与 Flyme Auto 之间的跨端连接能力，包含蓝牙配对连接、设备发现、应用流转和硬件共享；用户流程要求 Flyme 手机与车机建立 Flyme Link 连接。[Flyme Link 官方使用说明](https://open.flyme.cn/docs?id=311)

魅族公开的 FlymeLink SDK 隐私说明列出基础连接 SDK 可处理 Wi-Fi Direct MAC、蓝牙 MAC、网络与蓝牙开关状态、附近蓝牙设备和绑定设备记录，并列出联网、蓝牙和精确位置权限。但这只是数据处理与权限说明，没有公布可以调用的类、方法、AAR/Maven 坐标、车机侧绑定方式或第三方应用授权流程。[FlymeLink SDK 官方隐私说明](https://xj-platform.flyme.com/fms/policy/nocache/f38a362a2ead58b6bd57029dfefd157d.html)

魅族开放平台的 Flyme Link“接入文档”入口目前显示“暂无相关文档”，因此无法从官方公开资料完成可复现的 SDK 集成。[Flyme Link 接入文档入口](https://open.flyme.cn/docs?id=309)

根据上述边界，Flyme Link 值得向魅族开放平台申请 SDK 和车机端接入资格，但目前不能作为已经可用的 DiPlay 接口。官方用户流程面向 Flyme 手机；没有资料证明它支持 iPhone，也没有资料说明它可把厂商蓝牙连接转交给第三方应用或代替 iAP2/RFCOMM。即使 SDK 能让 DiPlay读取设备状态，也不等于 SDK 会实现 Apple CarPlay 配对和认证。

## ADB 授权结果与代码调整

新日志说明用户点击“允许 ADB”后没有收到授权提示。原实现等待授权超时后只显示“在车机屏幕上允许 DiPlay”，没有在未获批时打开系统开发者设置。本次调整在等待结束且 ADB 仍未授权时，尝试打开车机开发者设置；打开过程及系统是否解析出设置页面仍会记录在诊断日志中。应用不会自行授予 `WRITE_SECURE_SETTINGS`，也不会绕过系统签名权限。

若开发者设置不存在或被固件拦截，DiPlay 仍无法读取 `dumpsys bluetooth_manager`、`dumpsys wifi`、系统服务列表和进程列表。需要车机固件提供的显式授权，或魅族/吉利开放的 SDK/API 才能继续确认系统层接口。

## 后续实施依据

1. 若获得魅族开放平台提供的 FlymeLink SDK、接入文档和车机授权说明，再评估是否加入状态读取适配；先核实其是否运行在星愿 `E22H` 固件上，以及是否支持 iPhone 场景。
2. ADB 开发者设置成功启用并批准 DiPlay 后，再上传日志检查车机蓝牙服务、Wi-Fi AP/客户端和网卡路由；只读探测通过后才针对已证实的接口实现适配。
3. 继续保留广播、RFCOMM、iAP2、Wi-Fi 地址和 mDNS 各阶段日志。不得以接口猜测、模拟设备地址或跳过 iAP2 认证来报告连接成功。
