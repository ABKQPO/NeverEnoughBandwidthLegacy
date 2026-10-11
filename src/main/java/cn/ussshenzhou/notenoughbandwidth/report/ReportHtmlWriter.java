package cn.ussshenzhou.notenoughbandwidth.report;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.stat.PacketKindSample;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;

/**
 * Renders a report as one self-contained HTML file: no scripts beyond a theme toggle, no fonts, no
 * network, so it opens from a server's file system or an attached log archive.
 * <p>
 * The charts answer one question - which packet kinds are the stream - so they are single series
 * horizontal bars in one hue, with length carrying magnitude and the hue carrying nothing. Every
 * charted number is also present in the table below it, which is what keeps the file readable when
 * colour is unavailable.
 *
 * @author USS_Shenzhou
 */
public class ReportHtmlWriter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int CHART_WIDTH = 1000;
    private static final int LABEL_WIDTH = 300;
    private static final int BAR_X = 304;
    private static final int BAR_SPAN = 596;
    private static final int ROW_HEIGHT = 26;
    private static final int BAR_HEIGHT = 18;
    private static final int BAR_RADIUS = 4;
    private static final int TOP_PADDING = 8;
    private static final int AXIS_BAND = 32;
    private static final int CHART_ROWS = 15;
    private static final int TABLE_ROWS = 200;
    private static final int LABEL_CHARS = 40;

    public static void write(Writer out, ReportSnapshot snapshot) throws IOException {
        TrafficSnapshot traffic = snapshot.traffic();
        long raw = traffic.totalRawBytes();
        long wire = traffic.totalWireBytes();

        out.write("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        out.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        out.write("<title>" + ModConstants.MOD_NAME + " report - " + escape(snapshot.side()) + "</title>\n");
        writeStyle(out);
        out.write("</head>\n<body>\n<div class=\"viz-root\">\n");

        writeHeader(out, snapshot);
        writeHero(out, raw, wire);
        writeKpiRow(out, traffic, snapshot);
        writeChart(
            out,
            "Outgoing bytes by packet kind",
            "Pre-compression size of everything this side sent, heaviest kind first.",
            snapshot.outbound());
        writeChart(
            out,
            "Incoming bytes by packet kind",
            "Pre-compression size of everything this side received, heaviest kind first.",
            snapshot.inbound());
        writeSessionTable(out, snapshot);
        writePacketTable(out, "Outgoing packet kinds", snapshot.outbound());
        writePacketTable(out, "Incoming packet kinds", snapshot.inbound());
        writeMethod(out, snapshot);

        out.write("</div>\n");
        writeThemeToggleScript(out);
        out.write("</body>\n</html>\n");
    }

    private static void writeStyle(Writer out) throws IOException {
        out.write("<style>\n");
        out.write(
            ".viz-root{color-scheme:light;--surface-1:#fcfcfb;--page:#f9f9f7;--text-primary:#0b0b0b;"
                + "--text-secondary:#52514e;--text-muted:#898781;--gridline:#e1e0d9;--baseline:#c3c2b7;"
                + "--series-1:#2a78d6;--border:rgba(11,11,11,0.10);--good:#006300}\n");
        out.write(
            "@media (prefers-color-scheme: dark){:root:where(:not([data-theme=\"light\"])) .viz-root{"
                + "color-scheme:dark;--surface-1:#1a1a19;--page:#0d0d0d;--text-primary:#ffffff;"
                + "--text-secondary:#c3c2b7;--text-muted:#898781;--gridline:#2c2c2a;--baseline:#383835;"
                + "--series-1:#3987e5;--border:rgba(255,255,255,0.10);--good:#0ca30c}}\n");
        out.write(
            ":root[data-theme=\"dark\"] .viz-root{color-scheme:dark;--surface-1:#1a1a19;--page:#0d0d0d;"
                + "--text-primary:#ffffff;--text-secondary:#c3c2b7;--text-muted:#898781;--gridline:#2c2c2a;"
                + "--baseline:#383835;--series-1:#3987e5;--border:rgba(255,255,255,0.10);--good:#0ca30c}\n");
        out.write("html,body{margin:0;padding:0}\n");
        out.write(
            ".viz-root{background:var(--page);color:var(--text-primary);min-height:100vh;padding:32px 24px 64px;"
                + "font-family:system-ui,-apple-system,\"Segoe UI\",sans-serif;font-size:14px;line-height:1.5}\n");
        out.write(".wrap{max-width:1120px;margin:0 auto}\n");
        out.write("h1{font-size:20px;font-weight:600;margin:0 0 4px}\n");
        out.write("h2{font-size:15px;font-weight:600;margin:0 0 2px}\n");
        out.write(".sub{color:var(--text-secondary);margin:0 0 24px}\n");
        out.write(".note{color:var(--text-muted);font-size:12px;margin:2px 0 16px}\n");
        out.write(
            ".card{background:var(--surface-1);border:1px solid var(--border);border-radius:10px;"
                + "padding:20px;margin:0 0 20px}\n");
        out.write(".hero-value{font-size:52px;font-weight:600;letter-spacing:-0.02em;line-height:1.1}\n");
        out.write(".hero-label{color:var(--text-secondary);margin-bottom:6px}\n");
        out.write(
            ".kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:16px;"
                + "margin:0 0 20px}\n");
        out.write(".kpi{background:var(--surface-1);border:1px solid var(--border);border-radius:10px;padding:16px}\n");
        out.write(".kpi-label{color:var(--text-secondary);font-size:13px;margin-bottom:6px}\n");
        out.write(".kpi-value{font-size:24px;font-weight:600}\n");
        out.write(".kpi-foot{color:var(--text-muted);font-size:12px;margin-top:4px}\n");
        out.write("svg{display:block;width:100%;height:auto}\n");
        out.write(".bar{fill:var(--series-1)}\n");
        out.write(".bar-group:hover .bar{fill-opacity:0.82}\n");
        out.write(".grid{stroke:var(--gridline);stroke-width:1;shape-rendering:crispEdges}\n");
        out.write(".axis{stroke:var(--baseline);stroke-width:1;shape-rendering:crispEdges}\n");
        out.write(".tick{fill:var(--text-muted);font-size:11px;font-variant-numeric:tabular-nums}\n");
        out.write(".cat{fill:var(--text-secondary);font-size:12px}\n");
        out.write(".val{fill:var(--text-secondary);font-size:12px;font-variant-numeric:tabular-nums}\n");
        out.write("table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}\n");
        out.write("caption{text-align:left;font-weight:600;font-size:15px;padding:0 0 10px}\n");
        out.write(
            "th,td{text-align:right;padding:6px 10px;border-bottom:1px solid var(--border);"
                + "color:var(--text-primary)}\n");
        out.write("th{color:var(--text-secondary);font-weight:600;font-size:12px;white-space:nowrap}\n");
        out.write("th:first-child,td:first-child{text-align:left;font-variant-numeric:normal}\n");
        out.write(".good{color:var(--good)}\n");
        out.write(
            "#theme{position:fixed;top:16px;right:16px;background:var(--surface-1);color:var(--text-secondary);"
                + "border:1px solid var(--border);border-radius:8px;padding:6px 12px;font:inherit;font-size:12px;"
                + "cursor:pointer}\n");
        out.write("</style>\n");
    }

    private static void writeHeader(Writer out, ReportSnapshot snapshot) throws IOException {
        out.write("<button id=\"theme\" type=\"button\">Toggle theme</button>\n");
        out.write("<div class=\"wrap\">\n");
        out.write("<h1>" + ModConstants.MOD_NAME + " bandwidth report</h1>\n");
        out.write(
            "<p class=\"sub\">" + escape(snapshot.side())
                + " side &middot; "
                + escape(
                    STAMP.format(
                        Instant.ofEpochMilli(snapshot.takenAtMillis())
                            .atZone(ZoneId.systemDefault())))
                + " &middot; backend <strong>"
                + escape(snapshot.codec())
                + "</strong> ("
                + escape(snapshot.codecDetail())
                + ") &middot; aggregation window "
                + snapshot.aggregationWindowMillis()
                + " ms</p>\n");
    }

    private static void writeHero(Writer out, long raw, long wire) throws IOException {
        long saved = ByteSize.saved(raw, wire);
        String percent = raw <= 0 ? "n/a" : fmt(100.0D * saved / raw) + "%";
        out.write("<div class=\"card\">\n<div class=\"hero-label\">Bandwidth saved, both directions</div>\n");
        out.write("<div class=\"hero-value" + (saved > 0 ? " good" : "") + "\">" + percent + "</div>\n");
        out.write(
            "<div class=\"kpi-foot\">" + escape(ByteSize.format(saved))
                + " not sent, out of "
                + escape(ByteSize.format(raw))
                + " the vanilla stream would have carried</div>\n</div>\n");
    }

    private static void writeKpiRow(Writer out, TrafficSnapshot traffic, ReportSnapshot snapshot) throws IOException {
        out.write("<div class=\"kpis\">\n");
        kpi(
            out,
            "Outgoing on the wire",
            ByteSize.format(traffic.outboundWireBytes()),
            "from " + ByteSize.format(traffic.outboundRawBytes())
                + " raw, "
                + ByteSize.formatRatio(traffic.outboundRawBytes(), traffic.outboundWireBytes())
                + " of it");
        kpi(
            out,
            "Incoming on the wire",
            ByteSize.format(traffic.inboundWireBytes()),
            "from " + ByteSize.format(traffic.inboundRawBytes())
                + " raw, "
                + ByteSize.formatRatio(traffic.inboundRawBytes(), traffic.inboundWireBytes())
                + " of it");
        kpi(
            out,
            "Packets merged per frame",
            fmt(traffic.outboundPacketsPerFrame()) + " out",
            fmt(traffic.inboundPacketsPerFrame()) + " in, over " + traffic.outboundFrames() + " frames sent");
        kpi(
            out,
            "Current rate on the wire",
            ByteSize.formatRate(traffic.outboundWireRate()) + " out",
            ByteSize.formatRate(traffic.inboundWireRate()) + " in, across "
                + snapshot.sessions()
                    .size()
                + " connection(s)");
        out.write("</div>\n");
    }

    private static void kpi(Writer out, String label, String value, String foot) throws IOException {
        out.write(
            "<div class=\"kpi\"><div class=\"kpi-label\">" + escape(label)
                + "</div><div class=\"kpi-value\">"
                + escape(value)
                + "</div><div class=\"kpi-foot\">"
                + escape(foot)
                + "</div></div>\n");
    }

    private static void writeChart(Writer out, String title, String subtitle, List<PacketKindSample> samples)
        throws IOException {
        out.write("<div class=\"card\">\n<h2>" + escape(title) + "</h2>\n<p class=\"note\">" + escape(subtitle));
        if (samples.isEmpty()) {
            out.write(" No traffic recorded yet.</p>\n</div>\n");
            return;
        }
        int charted = Math.min(CHART_ROWS, samples.size());
        long folded = 0;
        for (int i = charted; i < samples.size(); i++) {
            folded += samples.get(i)
                .rawBytes();
        }
        int rows = charted + (folded > 0 ? 1 : 0);
        if (folded > 0) {
            out.write(
                " The remaining " + (samples.size() - charted)
                    + " kinds are folded into one row and listed in"
                    + " full in the table below.");
        }
        out.write("</p>\n");

        long maxValue = samples.get(0)
            .rawBytes();
        if (folded > maxValue) {
            maxValue = folded;
        }
        double axisMax = niceMax(maxValue);
        int plotBottom = TOP_PADDING + rows * ROW_HEIGHT;
        int height = plotBottom + AXIS_BAND;

        out.write(
            "<svg viewBox=\"0 0 " + CHART_WIDTH
                + ' '
                + height
                + "\" role=\"img\" aria-label=\""
                + escape(title)
                + "\">\n");
        for (int tick = 0; tick <= 4; tick++) {
            int x = BAR_X + BAR_SPAN * tick / 4;
            out.write(
                "<line class=\"" + (tick == 0 ? "axis" : "grid")
                    + "\" x1=\""
                    + x
                    + "\" y1=\""
                    + TOP_PADDING
                    + "\" x2=\""
                    + x
                    + "\" y2=\""
                    + plotBottom
                    + "\"/>\n");
            out.write(
                "<text class=\"tick\" x=\"" + x
                    + "\" y=\""
                    + (plotBottom + 18)
                    + "\" text-anchor=\"middle\">"
                    + escape(ByteSize.format(axisMax * tick / 4))
                    + "</text>\n");
        }
        for (int i = 0; i < charted; i++) {
            bar(
                out,
                i,
                samples.get(i)
                    .label(),
                samples.get(i)
                    .count(),
                samples.get(i)
                    .rawBytes(),
                axisMax);
        }
        if (folded > 0) {
            bar(out, charted, "Other (" + (samples.size() - charted) + " kinds)", 0, folded, axisMax);
        }
        out.write("</svg>\n</div>\n");
    }

    private static void bar(Writer out, int row, String label, long count, long value, double axisMax)
        throws IOException {
        int y = TOP_PADDING + row * ROW_HEIGHT + (ROW_HEIGHT - BAR_HEIGHT) / 2;
        int width = axisMax <= 0 ? 0 : (int) Math.round(BAR_SPAN * value / axisMax);
        out.write(
            "<g class=\"bar-group\"><title>" + escape(label)
                + ": "
                + escape(ByteSize.format(value))
                + (count > 0 ? " over " + count + " packets" : "")
                + "</title>\n");
        out.write(
            "<text class=\"cat\" x=\"" + (LABEL_WIDTH - 8)
                + "\" y=\""
                + (y + BAR_HEIGHT / 2)
                + "\" text-anchor=\"end\" dominant-baseline=\"central\">"
                + escape(clip(label))
                + "</text>\n");
        out.write("<path class=\"bar\" d=\"" + barPath(BAR_X, y, Math.max(width, 1), BAR_HEIGHT) + "\"/>\n");
        out.write(
            "<text class=\"val\" x=\"" + (BAR_X + Math.max(width, 1) + 8)
                + "\" y=\""
                + (y + BAR_HEIGHT / 2)
                + "\" dominant-baseline=\"central\">"
                + escape(ByteSize.format(value))
                + "</text>\n");
        out.write("</g>\n");
    }

    /**
     * Square where it meets the baseline, rounded at the data end.
     */
    private static String barPath(int x, int y, int width, int height) {
        if (width <= BAR_RADIUS * 2) {
            return "M" + x + ' ' + y + "h" + width + "v" + height + "h-" + width + "z";
        }
        int straight = width - BAR_RADIUS;
        return "M" + x
            + ' '
            + y
            + "h"
            + straight
            + "a"
            + BAR_RADIUS
            + ' '
            + BAR_RADIUS
            + " 0 0 1 "
            + BAR_RADIUS
            + ' '
            + BAR_RADIUS
            + "v"
            + (height - BAR_RADIUS * 2)
            + "a"
            + BAR_RADIUS
            + ' '
            + BAR_RADIUS
            + " 0 0 1 -"
            + BAR_RADIUS
            + ' '
            + BAR_RADIUS
            + "h-"
            + straight
            + "z";
    }

    private static void writeSessionTable(Writer out, ReportSnapshot snapshot) throws IOException {
        out.write("<div class=\"card\">\n<table>\n<caption>Live connections</caption>\n");
        out.write(
            "<thead><tr><th>Peer</th><th>Backend</th><th>Compressing</th><th>Out raw</th><th>Out wire</th>"
                + "<th>Out ratio</th><th>In raw</th><th>In wire</th><th>In ratio</th></tr></thead>\n<tbody>\n");
        if (snapshot.sessions()
            .isEmpty()) {
            out.write("<tr><td colspan=\"9\">No connections.</td></tr>\n");
        }
        for (SessionSample session : snapshot.sessions()) {
            TrafficSnapshot traffic = session.traffic();
            out.write(
                "<tr><td>" + escape(session.peer())
                    + "</td><td>"
                    + escape(session.codec())
                    + "</td><td>"
                    + (session.compressing() ? "yes" : "no")
                    + "</td><td>"
                    + escape(ByteSize.format(traffic.outboundRawBytes()))
                    + "</td><td>"
                    + escape(ByteSize.format(traffic.outboundWireBytes()))
                    + "</td><td>"
                    + escape(ByteSize.formatRatio(traffic.outboundRawBytes(), traffic.outboundWireBytes()))
                    + "</td><td>"
                    + escape(ByteSize.format(traffic.inboundRawBytes()))
                    + "</td><td>"
                    + escape(ByteSize.format(traffic.inboundWireBytes()))
                    + "</td><td>"
                    + escape(ByteSize.formatRatio(traffic.inboundRawBytes(), traffic.inboundWireBytes()))
                    + "</td></tr>\n");
        }
        out.write("</tbody>\n</table>\n</div>\n");
    }

    private static void writePacketTable(Writer out, String caption, List<PacketKindSample> samples)
        throws IOException {
        long total = 0;
        for (PacketKindSample sample : samples) {
            total += sample.rawBytes();
        }
        out.write("<div class=\"card\">\n<table>\n<caption>" + escape(caption) + "</caption>\n");
        out.write(
            "<thead><tr><th>Packet kind</th><th>Packets</th><th>Raw bytes</th><th>Share</th>"
                + "<th>Average</th></tr></thead>\n<tbody>\n");
        if (samples.isEmpty()) {
            out.write("<tr><td colspan=\"5\">Nothing recorded.</td></tr>\n");
        }
        int shown = Math.min(TABLE_ROWS, samples.size());
        for (int i = 0; i < shown; i++) {
            PacketKindSample sample = samples.get(i);
            out.write(
                "<tr><td>" + escape(sample.label())
                    + "</td><td>"
                    + sample.count()
                    + "</td><td>"
                    + escape(ByteSize.format(sample.rawBytes()))
                    + "</td><td>"
                    + (total <= 0 ? "n/a" : fmt(100.0D * sample.rawBytes() / total) + "%")
                    + "</td><td>"
                    + escape(ByteSize.format(sample.averageBytes()))
                    + "</td></tr>\n");
        }
        out.write("</tbody>\n</table>\n");
        if (shown < samples.size()) {
            out.write(
                "<p class=\"note\">" + (samples.size() - shown)
                    + " further kinds omitted here; the CSV export has all of them.</p>\n");
        }
        out.write("</div>\n");
    }

    private static void writeMethod(Writer out, ReportSnapshot snapshot) throws IOException {
        out.write("<div class=\"card\">\n<h2>How to read this</h2>\n<p class=\"note\">");
        out.write(
            "\"Raw\" is measured at the point Minecraft handed each packet to the wire and includes the length"
                + " prefix the vanilla stream would have paid for it, so it is what the connection would have cost"
                + " without this mod. \"Wire\" is what was actually written, aggregated frame header and length prefix"
                + " included. Both are counted in the same place, so the ratio between them is the real saving and not"
                + " an estimate. Per-kind figures are raw only: compression runs over a whole frame rather than one"
                + " packet, so a per-packet compressed size does not exist - the kind table tells you what to stop"
                + " sending, the ratio tells you what compression did with it.");
        out.write(
            "</p>\n<p class=\"note\">Backend " + escape(snapshot.codec())
                + ": "
                + escape(snapshot.codecDetail())
                + ".</p>\n</div>\n</div>\n");
    }

    private static void writeThemeToggleScript(Writer out) throws IOException {
        out.write("<script>\n");
        out.write("document.getElementById('theme').addEventListener('click',function(){\n");
        out.write("  var root=document.documentElement;\n");
        out.write("  var dark=root.getAttribute('data-theme')==='dark'\n");
        out.write("    ||(!root.hasAttribute('data-theme')&&matchMedia('(prefers-color-scheme: dark)').matches);\n");
        out.write("  root.setAttribute('data-theme',dark?'light':'dark');\n");
        out.write("});\n");
        out.write("</script>\n");
    }

    /**
     * Rounds the axis up to a value that divides into four readable ticks.
     */
    private static double niceMax(long max) {
        if (max <= 0) {
            return 1.0D;
        }
        double unit = 1.0D;
        while (max / unit >= 1024.0D) {
            unit *= 1024.0D;
        }
        double scaled = max / unit;
        double[] steps = { 1, 2, 4, 5, 8, 10, 16, 20, 32, 40, 64, 80, 100, 128, 256, 512, 1024 };
        for (double step : steps) {
            if (scaled <= step) {
                return step * unit;
            }
        }
        return scaled * unit;
    }

    private static String clip(String label) {
        return label.length() <= LABEL_CHARS ? label : label.substring(0, LABEL_CHARS - 1) + '…';
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> builder.append("&amp;");
                case '<' -> builder.append("&lt;");
                case '>' -> builder.append("&gt;");
                case '"' -> builder.append("&quot;");
                case '\'' -> builder.append("&#39;");
                default -> builder.append(c);
            }
        }
        return builder.toString();
    }

    private ReportHtmlWriter() {}
}
