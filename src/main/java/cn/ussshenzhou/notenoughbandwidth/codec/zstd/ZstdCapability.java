package cn.ussshenzhou.notenoughbandwidth.codec.zstd;

import java.nio.ByteBuffer;

import com.github.luben.zstd.Zstd;

public class ZstdCapability {

    private static final String PACKAGE = "com.github.luben.zstd.";

    private static final Boolean NATIVE_READY = probeNative();
    private static final Boolean STREAMING = NATIVE_READY && probeStreaming();
    private static final String VERSION = probeVersion();

    public static boolean blockSupported() {
        return NATIVE_READY;
    }

    public static boolean streamingSupported() {
        return STREAMING;
    }

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
