package cn.ussshenzhou.notenoughbandwidth.util;

import java.util.Locale;

public class ByteSize {

    private static final String[] UNITS = { "B", "KiB", "MiB", "GiB", "TiB" };

    public static String format(double bytes) {
        double value = bytes;
        int unit = 0;
        while (value >= 1024.0D && unit < UNITS.length - 1) {
            value /= 1024.0D;
            unit++;
        }
        return unit == 0 ? String.format(Locale.ROOT, "%.0f %s", value, UNITS[unit])
            : String.format(Locale.ROOT, "%.2f %s", value, UNITS[unit]);
    }

    public static String formatRate(double bytesPerSecond) {
        return format(bytesPerSecond) + "/s";
    }

    public static String formatRatio(long rawBytes, long wireBytes) {
        if (rawBytes <= 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1f%%", 100.0D * wireBytes / rawBytes);
    }

    public static double ratio(long rawBytes, long wireBytes) {
        return rawBytes <= 0 ? 1.0D : (double) wireBytes / rawBytes;
    }

    public static long saved(long rawBytes, long wireBytes) {
        return rawBytes - wireBytes;
    }

}
