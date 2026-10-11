package cn.ussshenzhou.notenoughbandwidth.codec.zstd;

import java.nio.ByteBuffer;

import com.github.luben.zstd.Zstd;

/**
 * Answers what the zstd-jni build that actually won class loading is able to do.
 * <p>
 * The package name of zstd-jni cannot be relocated, because the library binds its native symbols
 * through the standard JNI mangling of its Java class names. Several 1.7.10 mods therefore shade
 * {@code com.github.luben.zstd} unrelocated, and whichever jar the class loader reaches first is
 * the one everybody gets. Probing by name keeps us correct no matter which build that is: the
 * streaming context API only exists from 1.5.0 onwards, while the one-shot context API has been
 * there for much longer.
 *
 * @author USS_Shenzhou
 */
public class ZstdCapability {

    private static final String PACKAGE = "com.github.luben.zstd.";

    private static final Boolean NATIVE_READY = probeNative();
    private static final Boolean STREAMING = NATIVE_READY && probeStreaming();
    private static final String VERSION = probeVersion();

    /**
     * @return whether the native library loaded and the one-shot context API is usable
     */
    public static boolean blockSupported() {
        return NATIVE_READY;
    }

    /**
     * @return whether the context-per-connection streaming API is usable
     */
    public static boolean streamingSupported() {
        return STREAMING;
    }

    /**
     * @return the zstd-jni artifact version that won class loading, or {@code "unknown"}
     */
    public static String version() {
        return VERSION;
    }

    private static boolean probeNative() {
        try {
            return !Zstd.isError(Zstd.compressBound(1L));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean probeStreaming() {
        try {
            ClassLoader loader = ZstdCapability.class.getClassLoader();
            Class<?> endDirective = Class.forName(PACKAGE + "EndDirective", false, loader);
            Class<?> compressCtx = Class.forName(PACKAGE + "ZstdCompressCtx", false, loader);
            Class<?> decompressCtx = Class.forName(PACKAGE + "ZstdDecompressCtx", false, loader);
            compressCtx.getMethod("compressDirectByteBufferStream", ByteBuffer.class, ByteBuffer.class, endDirective);
            compressCtx.getMethod("setMagicless", boolean.class);
            compressCtx.getMethod("setWindowLog", int.class);
            decompressCtx.getMethod("decompressDirectByteBufferStream", ByteBuffer.class, ByteBuffer.class);
            decompressCtx.getMethod("setMagicless", boolean.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String probeVersion() {
        try {
            Class<?> version = Class.forName(PACKAGE + "util.ZstdVersion", true, ZstdCapability.class.getClassLoader());
            Object value = version.getField("VERSION")
                .get(null);
            return value == null ? "unknown" : value.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private ZstdCapability() {}
}
