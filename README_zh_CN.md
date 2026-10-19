# NeverEnoughBandwidthLegacy

[English](README.md) | 简体中文

Minecraft 1.7.10 的网络包优化 MOD，旨在改善服务器带宽受限时的多人联机体验。

> [!NOTE]
> 这是 NEB（[NotEnoughBandwidth](https://github.com/USS-Shenzhou/NotEnoughBandwidth)）的 1.7.10 移植版。使用本 MOD 时遇到的问题，请提交到本仓库；请勿提交到 NEB。

## 下载

| GTNH 版本 | Mod 版本 | 状态      | 下载                                                                                                                                    |
|-----------|----------|-----------|-----------------------------------------------------------------------------------------------------------------------------------------|
| 2.9.0 RC2 | 1.0.0+   | ✅ 维护中 | [![Latest](https://img.shields.io/badge/Download-Latest-success)](https://github.com/ABKQPO/NeverEnoughBandwidthLegacy/releases/latest) |

## 统计界面

默认按键 `Alt+N`

![流量统计界面](assets/stat_screen_zh.png)

## 功能特性

### ZSTD 流压缩

通过 `ZstdStreamCodec` 为连接保留 Zstandard 压缩上下文，跨聚合帧复用历史，利用之前传输的数据压缩后续内容，减少重复数据占用的带宽。服务端还可通过 `BandwidthDictionary` 针对每条连接的流量训练压缩字典，仅在验证有收益、能够抵消下发成本时使用。

客户端和服务端通过 `HelloMessage` 交换能力，由 `NebSession` 管理协商结果和连接上下文，支持 ZSTD 流、ZSTD 独立块、Deflate 流及原包直通；协商完成前保持原版传输。对于独立块和尚未预热的流，压缩没有收益时可回退为原包发送；流上下文重置会同步通知接收端。

### 更小包头

原版 NEB 在 `CustomPacketPayload` 的公共编解码入口压缩 `Identifier` 类型标识。1.7.10 对应的模组数据由 `S3FPacketCustomPayload`（服务端到客户端）和 `C17PacketCustomPayload`（客户端到服务端）承载，本项目在这些包序列化后的字节中识别通道名称，并使用紧凑索引替代重复名称，保留 payload 内部的消息编号和内容。

Forge 1.7.10 缺少新版 NeoForge 的统一 payload 类型与 codec 注册表，因此 `ChannelIndex` 按连接、按传输方向动态建立字典：第一次发送名称并定义索引，之后在压缩聚合帧中使用 1~2 字节的 VarInt 引用，不要求双方预先拥有相同的注册顺序。

这 1~2 字节仅指通道名的替代部分，不包括完整包头。每个字典最多保存 4096 个通道，满后新通道继续携带完整名称；接收端在交给原版/Forge 解码前恢复通道名，无需其他模组修改自己的网络代码。

### 包聚合

将短时间内的小包合并压缩发送，让多个包共享压缩开销，默认聚合窗口为 20 毫秒；使用流压缩时，极小批次可适当延长等待，以改善压缩效率。

通过 `NetworkManager` 和 Forge `NetworkDispatcher` 的生命周期钩子安装管线，`PacketAggregator` 收集原版 `MessageSerializer` 已编码的包体，写入 `NebFrame` 聚合帧；接收端的 `PacketExpander` 在 `MessageDeserializer2` 拆帧后解压、还原通道名，再将各个包体交回原版包解码器。

> [!NOTE]
> 普通压缩聚合帧结构如下；下方第二行展示压缩前的子包布局：
>
> ```text
> +---+---+---+-------------------------------------+
> | S | H | R |          compressed data            |
> +---+---+---+-------------------------------------+
>
>             +----+----+----+----+----+----+-----+
>             | s0 | p0 | d0 | s1 | p1 | d1 | ... |
>             +----+----+----+----+----+----+-----+
>             |-- packet 0 --|-- packet 1 --|
> ```
>
> - `S`：VarInt，外层帧长度，不含 `S` 自身。
> - `H`：1 字节，NEB 帧标记、压缩后端及上下文重置标志。
> - `R`：VarInt，压缩前聚合数据的字节数，通道索引替换已在此之前完成。
> - `s`：VarInt，当前子包的 `p + d` 总字节数。
> - `p`：原版包 ID（VarInt）；自定义 payload 还包含通道 token，以及首次定义或未索引时的通道名称。
> - `d`：该子包其余数据。
>
> `s0 + p0 + d0 + s1 + p1 + d1 + ...` 整体压缩后，写入 `compressed data`。

需要即时传输的通道和包类型可以绕过聚合；发送这些包之前会先发出缓冲内容，保持顺序。过大的单包沿用原版路径，聚合帧在完成长度和通道索引校验后才交给下游处理。

### 延迟区块缓存

暂时保留玩家刚离开视野的区块，减少在附近移动、往返时重复发送完整区块的数据量。默认每名玩家最多额外保留 128 个区块，范围为视距外 2 个区块，保留时间为 10 秒。

缓存通过 `PlayerManager` 和内部类 `PlayerInstance` 的订阅钩子保留和恢复，期间继续接收方块更新，并通过 `isPlayerWatchingChunk` 排除额外保留区块，避免扩大正常实体追踪范围。超过时间、距离或数量限制，以及玩家离开时，使用原版移除流程清理，无需替换区块写入器。

## 指令

| 指令               | 用法                  |
|--------------------|-----------------------|
| 查看服务端统计     | `/neb stat`           |
| 导出服务端报告     | `/neb report`         |
| 清空服务端统计     | `/neb reset`          |
| 查看可用压缩后端   | `/neb codec`          |
| 查看数据集采集状态 | `/neb capture status` |
| 开始采集           | `/neb capture start`  |
| 停止采集           | `/neb capture stop`   |

## 配置

配置文件为 `config/notenoughbandwidth.cfg`，客户端也可在模组列表中打开配置界面。

下列数值为默认值。客户端和服务端各自读取本地配置，压缩后端由服务端根据双方能力选择；区块缓存及按玩家禁用上下文的设置在服务端使用。修改配置文件后请重启对应客户端或服务端，确保连接协商、压缩器和绕过规则按新配置创建。

### enabled

默认 `true`，控制是否启用优化管线。修改此项需要重启游戏/服务端。

### codec

默认 `auto`，推荐保持自动选择；最终使用的后端仍需双方支持。

| 值               | 说明                                         |
|------------------|----------------------------------------------|
| `auto`           | 按运行环境和对端能力自动选择。               |
| `zstd_stream`    | 跨帧复用 ZSTD 压缩历史。                     |
| `zstd_block`     | 每帧独立压缩，不保留跨帧历史。               |
| `deflate_stream` | 使用 JDK 自带 Deflate 流，无需 ZSTD 原生库。 |
| `stored`         | 原包直通，不进行聚合压缩或通道索引替换。     |

### aggregationWindowMillis、smallPacketCoalescingMillis

聚合与小包等待时间，单位为毫秒。

| 配置项                        | 默认值 | 说明                                                                               |
|-------------------------------|--------|------------------------------------------------------------------------------------|
| `aggregationWindowMillis`     | `20`   | 普通聚合窗口，范围 0~200；设为 0 时仅合并 EventLoop 连续处理的包，不主动增加等待。 |
| `smallPacketCoalescingMillis` | `80`   | 流压缩处理极小批次时可等待至此时限，范围 0~500；设为 0 可关闭这项延长等待。        |

较长等待有利于小包压缩，但会增加延迟。

### flushThresholdBytes、compressionThresholdBytes、maxExpandedFrameBytes

| 配置项                      | 默认值               | 说明                                                     |
|-----------------------------|----------------------|----------------------------------------------------------|
| `flushThresholdBytes`       | `262144`（256 KiB）  | 缓冲达到此大小时提前发送；聚合数据另有 1 MiB 硬上限。    |
| `compressionThresholdBytes` | `64`                 | 独立帧低于此大小时不压缩；流压缩可在合并后尝试更小批次。 |
| `maxExpandedFrameBytes`     | `16777216`（16 MiB） | 接收帧解压/还原大小上限，同时限制发送端聚合大小。        |

### unaggregatedChannels、unaggregatedPacketTypes

指定直接沿用原版路径、不参与聚合压缩的通道名称和包类名。默认绕过通道为 `FML|HS`、`REGISTER`、`UNREGISTER`、`MC|Brand`；包类型支持完整类名或简单类名，默认包含部分实体、移动、聊天等对延迟敏感的包。

> [!NOTE]
> 绕过包会先触发缓冲内容发送，以保持顺序；绕过规则过多或命中频繁时，会降低聚合效率。修改这两个列表需要重启。

### zstdLevel、zstdWindowLog

| 配置项          | 默认值 | 说明                                                               |
|-----------------|--------|--------------------------------------------------------------------|
| `zstdLevel`     | `3`    | ZSTD 压缩级别，范围 1~19；更高通常能节省更多流量，但消耗更多 CPU。 |
| `zstdWindowLog` | `22`   | ZSTD 流历史窗口的以 2 为底的对数，范围 17~27；22 对应 4 MiB 窗口。 |

较大的窗口会增加每条连接的原生内存开销，压缩和解压上下文都需要内存；窗口大小不等于整条连接的总内存占用。

### enableBandwidthDictionary

默认 `true`，允许服务端按连接采样并训练有大小限制的 ZSTD 字典。仅在双方支持、验证有收益且满足下发条件时发送更新，不会为所有连接无条件下发字典。

### delayedChunkCache、delayedChunkCacheSize、delayedChunkCacheDistance、delayedChunkCacheSeconds

> [!NOTE]
> 以下区块缓存设置在服务端使用。

| 配置项                      | 默认值 | 说明                                                              |
|-----------------------------|--------|-------------------------------------------------------------------|
| `delayedChunkCache`         | `true` | 启用延迟区块缓存；修改需要重启。                                  |
| `delayedChunkCacheSize`     | `128`  | 每名玩家最多额外保留的区块数，范围 0~2048；0 表示不保留。         |
| `delayedChunkCacheDistance` | `2`    | 视距之外允许保留的额外距离，单位为区块，范围 0~16；0 表示不保留。 |
| `delayedChunkCacheSeconds`  | `10`   | 区块离开视野后的最长保留秒数，范围 1~120。                        |

较大的保留范围、数量和时间可以减少往返时的重传，但也会增加服务端和客户端的资源占用。

### playersWithoutContext

服务端设置，默认为空列表。接受玩家名或 UUID，不区分大小写；名单内玩家会协商使用不保留跨帧压缩历史的后端，以降低上下文内存开销，但压缩率可能下降。

### collectPacketProfile、datasetCaptureSeconds、datasetCaptureMiB、debugLog

| 配置项                  | 默认值  | 说明                                                  |
|-------------------------|---------|-------------------------------------------------------|
| `collectPacketProfile`  | `true`  | 按包类型与模组通道记录统计，供详细报告使用。          |
| `datasetCaptureSeconds` | `120`   | 数据集采集默认时长，范围 1~3600 秒。                  |
| `datasetCaptureMiB`     | `64`    | 单次采集 payload 大小上限，范围 1~1024 MiB。          |
| `debugLog`              | `false` | 在 debug 日志中记录逐帧压缩率，仅建议排查问题时启用。 |

## Credit

感谢 USS_Shenzhou 及原始 [NotEnoughBandwidth](https://github.com/USS-Shenzhou/NotEnoughBandwidth) 项目的贡献者，为本项目提供网络包优化的基础与实现思路。
