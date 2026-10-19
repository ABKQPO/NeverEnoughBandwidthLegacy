package cn.ussshenzhou.notenoughbandwidth.report;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Locale;

import cn.ussshenzhou.notenoughbandwidth.stat.PacketKindSample;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;

public class ReportCsvWriter {

    private static final char SEPARATOR = ',';

    public static void writeTraffic(Writer writer, ReportSnapshot snapshot) throws IOException {
        header(
            writer,
            "scope",
            "name",
            "direction",
            "raw_bytes",
            "wire_bytes",
            "frames",
            "packets",
            "raw_bytes_per_second",
            "wire_bytes_per_second",
            "wire_percent_of_raw",
            "packets_per_frame",
            "profile_raw_bytes",
            "profile_coverage_percent");
        TrafficSnapshot total = snapshot.traffic();
        outboundRow(writer, "total", snapshot.side(), total, profileRawBytes(snapshot.outbound()), true);
        inboundRow(writer, "total", snapshot.side(), total, profileRawBytes(snapshot.inbound()), true);
        for (SessionSample session : snapshot.sessions()) {
            outboundRow(writer, "connection", session.peer(), session.traffic(), 0L, false);
            inboundRow(writer, "connection", session.peer(), session.traffic(), 0L, false);
        }
    }

    public static void writePackets(Writer writer, ReportSnapshot snapshot) throws IOException {
        header(
            writer,
            "direction",
            "packet_kind",
            "packets",
            "raw_bytes",
            "percent_of_direction",
            "average_bytes_per_packet");
        packetRows(
            writer,
            "outbound",
            snapshot.outbound(),
            snapshot.traffic()
                .outboundRawBytes());
        packetRows(
            writer,
            "inbound",
            snapshot.inbound(),
            snapshot.traffic()
                .inboundRawBytes());
    }

    private static void packetRows(Writer writer, String direction, List<PacketKindSample> samples, long total)
        throws IOException {
        for (PacketKindSample sample : samples) {
            row(
                writer,
                direction,
                sample.label(),
                String.valueOf(sample.count()),
                String.valueOf(sample.rawBytes()),
                percent(sample.rawBytes(), total),
                decimal(sample.averageBytes()));
        }
    }

    private static void outboundRow(Writer writer, String scope, String name, TrafficSnapshot traffic,
        long profileRawBytes, boolean includeProfile) throws IOException {
        row(
            writer,
            scope,
            name,
            "outbound",
            String.valueOf(traffic.outboundRawBytes()),
            String.valueOf(traffic.outboundWireBytes()),
            String.valueOf(traffic.outboundFrames()),
            String.valueOf(traffic.outboundPackets()),
            decimal(traffic.outboundRawRate()),
            decimal(traffic.outboundWireRate()),
            percent(traffic.outboundWireBytes(), traffic.outboundRawBytes()),
            decimal(traffic.outboundPacketsPerFrame()),
            includeProfile ? String.valueOf(profileRawBytes) : "",
            includeProfile ? percent(profileRawBytes, traffic.outboundRawBytes()) : "");
    }

    private static void inboundRow(Writer writer, String scope, String name, TrafficSnapshot traffic,
        long profileRawBytes, boolean includeProfile) throws IOException {
        row(
            writer,
            scope,
            name,
            "inbound",
            String.valueOf(traffic.inboundRawBytes()),
            String.valueOf(traffic.inboundWireBytes()),
            String.valueOf(traffic.inboundFrames()),
            String.valueOf(traffic.inboundPackets()),
            decimal(traffic.inboundRawRate()),
            decimal(traffic.inboundWireRate()),
            percent(traffic.inboundWireBytes(), traffic.inboundRawBytes()),
            decimal(traffic.inboundPacketsPerFrame()),
            includeProfile ? String.valueOf(profileRawBytes) : "",
            includeProfile ? percent(profileRawBytes, traffic.inboundRawBytes()) : "");
    }

    private static void header(Writer writer, String... columns) throws IOException {
        row(writer, columns);
    }

    private static void row(Writer writer, String... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                writer.write(SEPARATOR);
            }
            writer.write(escape(cells[i]));
        }
        writer.write('\n');
    }

    private static String escape(String cell) {
        if (cell == null) {
            return "";
        }
        if (cell.indexOf(SEPARATOR) < 0 && cell.indexOf('"') < 0 && cell.indexOf('\n') < 0) {
            return cell;
        }
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String percent(long part, long whole) {
        return whole <= 0 ? "" : String.format(Locale.ROOT, "%.2f", 100.0D * part / whole);
    }

    private static long profileRawBytes(List<PacketKindSample> samples) {
        long total = 0L;
        for (PacketKindSample sample : samples) {
            total = Long.MAX_VALUE - total < sample.rawBytes() ? Long.MAX_VALUE : total + sample.rawBytes();
        }
        return total;
    }

    /**
     * Kept here so the chat summary and the CSV cannot drift apart.
     */
    public static String ratioText(long rawBytes, long wireBytes) {
        return ByteSize.formatRatio(rawBytes, wireBytes);
    }
}
