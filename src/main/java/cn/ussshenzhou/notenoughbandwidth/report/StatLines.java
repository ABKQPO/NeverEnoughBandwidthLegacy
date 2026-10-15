package cn.ussshenzhou.notenoughbandwidth.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.util.EnumChatFormatting;

import cn.ussshenzhou.notenoughbandwidth.stat.PacketKindSample;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;

public class StatLines {

    private static final String KEY = EnumChatFormatting.GRAY.toString();
    private static final String VALUE = EnumChatFormatting.WHITE.toString();
    private static final String HEAD = EnumChatFormatting.AQUA.toString();
    private static final String GOOD = EnumChatFormatting.GREEN.toString();

    public static List<String> summary(String title, TrafficSnapshot traffic, String codec, int connections) {
        long raw = traffic.totalRawBytes();
        long wire = traffic.totalWireBytes();
        List<String> lines = new ArrayList<>();
        lines.add(
            HEAD + title + KEY + " via " + VALUE + codec + KEY + ", " + VALUE + connections + KEY + " connection(s)");
        lines.add(
            KEY + "  saved "
                + GOOD
                + percent(raw, wire)
                + KEY
                + " ("
                + VALUE
                + ByteSize.format(ByteSize.saved(raw, wire))
                + KEY
                + " of "
                + VALUE
                + ByteSize.format(raw)
                + KEY
                + ")");
        lines.add(
            KEY + "  out "
                + VALUE
                + ByteSize.format(traffic.outboundRawBytes())
                + KEY
                + " -> "
                + VALUE
                + ByteSize.format(traffic.outboundWireBytes())
                + KEY
                + " ("
                + ByteSize.formatRatio(traffic.outboundRawBytes(), traffic.outboundWireBytes())
                + "), now "
                + VALUE
                + ByteSize.formatRate(traffic.outboundWireRate()));
        lines.add(
            KEY + "  in  "
                + VALUE
                + ByteSize.format(traffic.inboundRawBytes())
                + KEY
                + " -> "
                + VALUE
                + ByteSize.format(traffic.inboundWireBytes())
                + KEY
                + " ("
                + ByteSize.formatRatio(traffic.inboundRawBytes(), traffic.inboundWireBytes())
                + "), now "
                + VALUE
                + ByteSize.formatRate(traffic.inboundWireRate()));
        lines.add(
            KEY + "  merged "
                + VALUE
                + decimal(traffic.outboundPacketsPerFrame())
                + KEY
                + " packets per frame"
                + " out, "
                + VALUE
                + decimal(traffic.inboundPacketsPerFrame())
                + KEY
                + " in");
        return lines;
    }

    public static List<String> topKinds(String title, List<PacketKindSample> samples, int limit) {
        List<String> lines = new ArrayList<>();
        lines.add(HEAD + title);
        if (samples.isEmpty()) {
            lines.add(KEY + "  nothing recorded yet");
            return lines;
        }
        long total = 0;
        for (PacketKindSample sample : samples) {
            total += sample.rawBytes();
        }
        int shown = Math.min(limit, samples.size());
        for (int i = 0; i < shown; i++) {
            PacketKindSample sample = samples.get(i);
            lines.add(
                KEY + "  "
                    + VALUE
                    + ByteSize.format(sample.rawBytes())
                    + KEY
                    + " ("
                    + share(sample.rawBytes(), total)
                    + ") "
                    + VALUE
                    + sample.label());
        }
        if (shown < samples.size()) {
            lines.add(KEY + "  ... and " + (samples.size() - shown) + " more, see /neb report");
        }
        return lines;
    }

    private static String share(long part, long whole) {
        return whole <= 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0D * part / whole);
    }

    private static String percent(long raw, long wire) {
        return raw <= 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0D * (raw - wire) / raw);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
