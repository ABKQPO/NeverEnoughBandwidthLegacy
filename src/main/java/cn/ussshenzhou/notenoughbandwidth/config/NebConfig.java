package cn.ussshenzhou.notenoughbandwidth.config;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

import cn.ussshenzhou.notenoughbandwidth.Tags;

@Config(modid = Tags.MODID, filename = "notenoughbandwidth")
@Config.LangKeyPattern(pattern = "neb.config.%cat.%field", fullyQualified = true)
@Config.Comment("NotEnoughBandwidth - aggregates and compresses the Minecraft packet stream.")
public class NebConfig {

    @Config.Comment({ "Master switch. When off, NEB installs nothing and the vanilla packet stream is untouched." })
    @Config.DefaultBoolean(true)
    @Config.RequiresMcRestart
    public static boolean enabled = true;

    @Config.Comment({ "Compression backend.",
        "auto          - pick the best backend the runtime actually supports (recommended)",
        "zstd_stream   - zstd with a context kept alive across frames, best ratio",
        "zstd_block    - zstd, one independent block per frame, works with older zstd-jni builds",
        "deflate_stream- JDK deflate with a 32 KiB sliding window, no native code",
        "stored        - pass through ordinary Minecraft frames without compression" })
    @Config.DefaultString("auto")
    public static String codec = "auto";

    @Config.Comment({ "How long outgoing packets may be held back to be merged into one frame, in milliseconds.",
        "Larger values compress better because more packets share one compression flush, at the cost of latency.",
        "0 merges only the packets that the Netty event loop happens to process back to back, adding no latency." })
    @Config.DefaultInt(20)
    @Config.RangeInt(min = 0, max = 200)
    public static int aggregationWindowMillis = 20;

    @Config.Comment({ "Additional delay for stateful codecs when a batch is too small to compress efficiently.",
        "Set to 0 to keep the configured aggregation window as the hard latency limit." })
    @Config.DefaultInt(80)
    @Config.RangeInt(min = 0, max = 500)
    public static int smallPacketCoalescingMillis = 80;

    @Config.Comment({ "Flush early once this many raw bytes are buffered. Bounds both latency and memory.",
        "Aggregation is also capped at 1 MiB to fit Minecraft 1.7.10's frame length limit.",
        "Individual packets above that cap are sent as ordinary Minecraft frames." })
    @Config.DefaultInt(262144)
    @Config.RangeInt(min = 4096, max = 8388608)
    public static int flushThresholdBytes = 262144;

    @Config.Comment({ "CustomPayload channels that flush pending packets and bypass aggregation and compression.",
        "Use this for handshake, registration, proxy, or mod control channels that require immediate vanilla delivery.",
        "The NEB negotiation channel is handled before compression and does not need to be listed." })
    @Config.DefaultStringList({ "FML|HS", "REGISTER", "UNREGISTER", "MC|Brand" })
    @Config.RequiresMcRestart
    public static String[] unaggregatedChannels = { "FML|HS", "REGISTER", "UNREGISTER", "MC|Brand" };

    @Config.Comment({
        "Packet classes that stay on the vanilla path because they are latency sensitive or frequently change.",
        "Names may be fully qualified or simple class names. The list is evaluated against the active packet registry on each connection." })
    @Config.DefaultStringList({ "S0CPacketSpawnPlayer", "S12PacketEntityVelocity", "S14PacketEntity",
        "S14PacketEntity$S15PacketEntityRelMove", "S18PacketEntityTeleport", "S0EPacketSpawnObject",
        "S13PacketDestroyEntities", "S23PacketBlockChange", "S02PacketChat", "S38PacketPlayerListItem",
        "S07PacketRespawn", "S08PacketPlayerPosLook", "S06PacketUpdateHealth", "S1EPacketEntityDisplace",
        "S44PacketWorldBorder" })
    @Config.RequiresMcRestart
    public static String[] unaggregatedPacketTypes = { "S0CPacketSpawnPlayer", "S12PacketEntityVelocity",
        "S14PacketEntity", "S14PacketEntity$S15PacketEntityRelMove", "S18PacketEntityTeleport", "S0EPacketSpawnObject",
        "S13PacketDestroyEntities", "S23PacketBlockChange", "S02PacketChat", "S38PacketPlayerListItem",
        "S07PacketRespawn", "S08PacketPlayerPosLook", "S06PacketUpdateHealth", "S1EPacketEntityDisplace",
        "S44PacketWorldBorder" };

    @Config.Comment({
        "Independent frames smaller than this are sent uncompressed; stateful streams may probe smaller batches after adaptive coalescing." })
    @Config.DefaultInt(64)
    @Config.RangeInt(min = 0, max = 65536)
    public static int compressionThresholdBytes = 64;

    @Config.Comment({ "Hard cap on the size a received frame is allowed to expand to. Guards against hostile peers." })
    @Config.DefaultInt(16777216)
    @Config.RangeInt(min = 1048576, max = 134217728)
    public static int maxExpandedFrameBytes = 16777216;

    @Config.Comment({ "zstd compression level. 1 is fastest, 19 is smallest; 3 is the zstd default." })
    @Config.DefaultInt(3)
    @Config.RangeInt(min = 1, max = 19)
    public static int zstdLevel = 3;

    @Config.Comment({ "Base 2 logarithm of the zstd window size, i.e. how much history a connection remembers.",
        "Each connection costs roughly 2^windowLog bytes of native memory for the compressor and the same again",
        "for the decompressor, so 22 is about 4 MiB + 4 MiB per player. Only used by the zstd_stream backend." })
    @Config.DefaultInt(22)
    @Config.RangeInt(min = 17, max = 27)
    public static int zstdWindowLog = 22;

    @Config.Comment({ "Train bounded per-connection server dictionaries for peers advertising dictionary support.",
        "Candidates must improve production-stream compression and repay their transfer cost; legacy peers keep their existing format." })
    @Config.DefaultBoolean(true)
    public static boolean enableBandwidthDictionary = true;

    @Config.Comment({ "Retain recently sent chunks briefly to avoid retransmission when a player returns.",
        "Retained chunks still receive block updates, but do not start new entity tracking.",
        "Existing entity tracking follows vanilla distance rules." })
    @Config.DefaultBoolean(true)
    @Config.RequiresMcRestart
    public static boolean delayedChunkCache = true;

    @Config.Comment("Maximum retained chunks per player, in addition to the normal view distance.")
    @Config.DefaultInt(128)
    @Config.RangeInt(min = 0, max = 2048)
    public static int delayedChunkCacheSize = 128;

    @Config.Comment("Maximum distance beyond the actual view radius for retained chunks.")
    @Config.DefaultInt(2)
    @Config.RangeInt(min = 0, max = 16)
    public static int delayedChunkCacheDistance = 2;

    @Config.Comment("Maximum time in seconds to retain a chunk after it leaves the player's view.")
    @Config.DefaultInt(10)
    @Config.RangeInt(min = 1, max = 120)
    public static int delayedChunkCacheSeconds = 10;

    @Config.Comment({ "Players listed here get a backend that keeps no per-connection history, trading ratio",
        "for memory. Use it for a player on a server too tight on native memory to afford a window.",
        "Accepts player names or UUIDs; matching is case insensitive." })
    @Config.DefaultStringList({})
    public static String[] playersWithoutContext = {};

    @Config.Comment({ "Track bytes and compression ratio per packet id and per mod channel.",
        "Needed by '/neb report'; costs one map lookup per packet." })
    @Config.DefaultBoolean(true)
    public static boolean collectPacketProfile = true;

    @Config.Comment("Default capture duration in seconds for /neb capture start.")
    @Config.DefaultInt(120)
    @Config.RangeInt(min = 1, max = 3600)
    public static int datasetCaptureSeconds = 120;

    @Config.Comment("Maximum captured payload size in MiB for one dataset.")
    @Config.DefaultInt(64)
    @Config.RangeInt(min = 1, max = 1024)
    public static int datasetCaptureMiB = 64;

    @Config.Comment({ "Log every frame's compression ratio at debug level. Very noisy, for troubleshooting only." })
    @Config.DefaultBoolean(false)
    public static boolean debugLog = false;

    public static boolean deniesContext(String name, String uuid) {
        for (String entry : playersWithoutContext) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            if (entry.equalsIgnoreCase(name) || entry.equalsIgnoreCase(uuid)) {
                return true;
            }
        }
        return false;
    }

    public static void register() throws ConfigException {
        ConfigurationManager.registerConfig(NebConfig.class);
    }

    private NebConfig() {}
}
