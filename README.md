# NeverEnoughBandwidthLegacy

English | [简体中文](README_zh_CN.md)

A network optimization mod for Minecraft 1.7.10, designed to improve multiplayer gameplay on bandwidth-limited servers.

> [!NOTE]
> This is a 1.7.10 port of NEB ([NotEnoughBandwidth](https://github.com/USS-Shenzhou/NotEnoughBandwidth)). Please report issues with this port to this repository, rather than to NEB.

## Download

| GTNH Version | Mod Version | Status                 | Download                                                                                                                                |
|--------------|-------------|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| 2.9.0 RC2    | 1.0.0+      | ✅ Actively Maintained | [![Latest](https://img.shields.io/badge/Download-Latest-success)](https://github.com/ABKQPO/NeverEnoughBandwidthLegacy/releases/latest) |

## Traffic statistics screen

Default shortcut: `Alt+N`

![Traffic statistics screen](assets/stat_screen_en.png)

## Features

- **ZSTD streaming compression**: Reuses compression history across packets to reduce the bandwidth consumed by repeated data.
- **Smaller headers**: Replaces repeated channel names with compact channel indices to reduce mod packet header overhead.
- **Packet aggregation**: Combines small packets sent within a short interval and compresses them together to reduce per-packet overhead.
- **Chunk caching**: Temporarily retains recently visited chunks to reduce full chunk retransmissions when players return to nearby areas.

## Commands

| Command                      | Usage                 |
|------------------------------|-----------------------|
| View server statistics       | `/neb stat`           |
| Export a server report       | `/neb report`         |
| Reset server statistics      | `/neb reset`          |
| Check dataset capture status | `/neb capture status` |
| Start capture                | `/neb capture start`  |
| Stop capture                 | `/neb capture stop`   |

---

## Technical Differences from NEB

This port reimplements channel indexing, aggregate frames, and connection negotiation for the Forge 1.7.10 network stack. The main technical differences are described below.

### Pipeline Integration Without a Unified Payload Registry

NEB filters and sorts the negotiated NeoForge PLAY channel list and payload registrations to build a two-level `namespace-id + path-id` index, then modifies the shared `CustomPacketPayload` codec entry point. Forge 1.7.10 provides channel registration, but lacks an equivalent unified registry covering mod payload types and codecs, so this implementation cannot be ported directly.

This port installs Netty handlers through lifecycle Mixins in `NetworkManager` and Forge's `NetworkDispatcher`: `PacketAggregator` is placed before `MessageSerializer`, and `PacketExpander` after the frame splitter, `MessageDeserializer2`. Since Netty processes outbound events in reverse pipeline order, the aggregator receives bytes already produced by the vanilla encoder, while the receiving side restores those bytes before vanilla packet decoding. Pipeline anchors are located by both name and type; installation and retries run on the connection's EventLoop, and local channels are skipped.

### Header Compression with Connection-Local Channel Dictionaries

This port locates the packet IDs of `S3FPacketCustomPayload` and `C17PacketCustomPayload` in the connection's current outbound and inbound protocol tables, then identifies channel names in serialized packet bodies. Each index represents a complete channel string; message IDs and data inside the mod's payload remain unchanged. Each direction of each connection maintains its own dictionary and assigns IDs in order of first appearance, so both peers do not need matching registration orders:

- First occurrence: Sends the VarInt token `(id << 1) | 1`, followed by the UTF-8 byte length and channel name, allowing the receiver to define the index.
- Subsequent references: Sends only the VarInt token `id << 1`. Tokens for IDs 1–63 occupy 1 byte; all other assigned IDs occupy 2 bytes. This covers only the channel name replacement, excluding the vanilla packet ID and frame header.
- Each dictionary holds up to 4096 channels. Once full, new channels use token `0` followed by the complete name, while existing channels can still use indices. Packets that bypass aggregation retain their vanilla format.

These substitutions apply only within compressed aggregate frames. The receiver checks definition order, index bounds, and name lengths, then restores the vanilla channel name and passes the packet back to the vanilla/Forge decoding path. Other mods do not need to change their serialization code. If a compression attempt falls back to sending the original packets, newly allocated indices are rolled back to avoid referencing definitions the peer never received. Dictionaries are cleared when the connection ends. Channel indices and Zstandard compression dictionaries are separate mechanisms.

### Aggregation Boundaries and Packet Ordering

NEB collects packet objects at `Connection.send` and uses their types and codecs to encode a custom aggregate payload. This port collects bytes after packet encoding, groups them into consecutive `VarInt length + vanilla packet body` entries, and adds frame metadata such as the codec, context reset flag, and decompressed length. After decompression and channel name restoration, the receiver passes individual packet bodies to the vanilla decoder.

Each connection's EventLoop manages its aggregation buffer and scheduled sends. Buffered data is sent before bypassed packets, oversized packets, or connection closure to preserve ordering. Frame sizes are constrained by both Minecraft's outer frame length limit and the configured decompressed size limit; individual packets exceeding the aggregation limit use the vanilla path. The receiver validates packet lengths and passes data downstream only after every packet has been split and every channel index restored successfully. Parsing failures release temporary buffers and raise an exception.

### Compression Backends and Context Synchronization

NEB uses Zstandard contexts and allows context reuse to be disabled per player. This port abstracts compression behind codecs and uses `HelloMessage` to exchange protocol versions, capabilities, and the server's selection, which is enabled after client confirmation. Available paths include Zstandard streaming, independent Zstandard blocks, Deflate streaming, and vanilla packet passthrough. Selection depends on both peers' available capabilities and configuration; packets continue along the vanilla path until negotiation completes.

For independent blocks or streams that have not warmed up, packets may be sent in their original form if compression provides no savings. If a streaming compressor has already consumed that batch, the sender recreates its context and marks the next compressed frame with a reset flag to synchronize the receiver. Warm streams may retain slightly larger frames to preserve history for subsequent compression. The server can also train shared Zstandard dictionaries from traffic samples; when both peers support them and distribution conditions are met, it sends a dictionary update frame before encoding subsequent data with the new dictionary.

### Delayed Chunk Cache Integration

NEB overwrites the tracking view update logic in `ChunkMap`. This port instead adds retention and restoration hooks to the 1.7.10 `PlayerManager` and `PlayerInstance`: when a player moves, subscriptions to already-sent chunks are temporarily retained; returning reuses existing chunks and refreshes entity tracking. Timeout, distance, capacity limits, or player departure trigger cleanup through the vanilla removal path. Retained chunks are excluded from `isPlayerWatchingChunk` results so the extra retained area is not treated as part of the normal entity tracking range. A separate Mixin handles ChunkAPI-compatible bulk chunk reads without replacing chunk writers.
