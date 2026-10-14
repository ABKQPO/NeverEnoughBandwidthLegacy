package cn.ussshenzhou.notenoughbandwidth.config;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;

/**
 * Every tunable of the mod. Registered from the core mod so that the pipeline can read it before
 * the first connection is accepted.
 *
 * @author USS_Shenzhou
 */
@Config(modid = ModConstants.MOD_ID, filename = "notenoughbandwidth", category = "general")
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

    @Config.Comment({ "Flush early once this many raw bytes are buffered. Bounds both latency and memory.",
        "Aggregation is also capped at 1 MiB to fit Minecraft 1.7.10's frame length limit.",
        "Individual packets above that cap are sent as ordinary Minecraft frames." })
    @Config.DefaultInt(262144)
    @Config.RangeInt(min = 4096, max = 8388608)
    public static int flushThresholdBytes = 262144;

    @Config.Comment({ "Frames smaller than this are sent uncompressed; compressing them costs more than it saves." })
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

    @Config.Comment({ "Track bytes and compression ratio per packet id and per mod channel.",
        "Needed by '/neb report'; costs one map lookup per packet." })
    @Config.DefaultBoolean(true)
    public static boolean collectPacketProfile = true;

    @Config.Comment({ "Log every frame's compression ratio at debug level. Very noisy, for troubleshooting only." })
    @Config.DefaultBoolean(false)
    public static boolean debugLog = false;

    public static void register() throws ConfigException {
        ConfigurationManager.registerConfig(NebConfig.class);
    }

    private NebConfig() {}
}
