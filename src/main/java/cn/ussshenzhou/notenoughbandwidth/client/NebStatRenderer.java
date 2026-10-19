package cn.ussshenzhou.notenoughbandwidth.client;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;

import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;

public class NebStatRenderer {

    private static final int PANEL_BACKGROUND = 0xB010171E;
    private static final int PANEL_EDGE = 0xFF2C3E49;
    private static final int ACCENT = 0xFF6EA8FF;
    private static final int TITLE = 0xFFEAF4F8;
    private static final int LABEL = 0xFF91A5B1;
    private static final int VALUE = 0xFFD8E7ED;

    public static void drawHeader(FontRenderer font, int width, String status) {
        Gui.drawRect(10, 8, width - 10, 29, 0xC0141D24);
        Gui.drawRect(10, 8, width - 10, 9, PANEL_EDGE);
        font.drawString(I18n.format("neb.stat.title"), 18, 14, TITLE);
        String state = I18n.format(status);
        int stateWidth = font.getStringWidth(state);
        Gui.drawRect(width - stateWidth - 28, 12, width - 18, 25, 0xAA1D3340);
        font.drawString(state, width - stateWidth - 23, 15, ACCENT);
    }

    public static void drawCard(FontRenderer font, int x, int y, int width, int height, String title,
        TrafficSnapshot snapshot, String codec) {
        drawPanel(x, y, width, height, ACCENT);
        font.drawString(title, x + 12, y + 9, TITLE);
        drawDirection(
            font,
            x + 12,
            y + 31,
            width - 24,
            I18n.format("neb.stat.inbound"),
            snapshot.inboundRawBytes(),
            snapshot.inboundWireBytes(),
            snapshot.inboundRawRate(),
            snapshot.inboundWireRate(),
            snapshot.inboundFrames(),
            snapshot.inboundPackets());
        drawDirection(
            font,
            x + 12,
            y + 79,
            width - 24,
            I18n.format("neb.stat.outbound"),
            snapshot.outboundRawBytes(),
            snapshot.outboundWireBytes(),
            snapshot.outboundRawRate(),
            snapshot.outboundWireRate(),
            snapshot.outboundFrames(),
            snapshot.outboundPackets());
        if (codec != null && !codec.isEmpty()) {
            drawSingleLine(font, I18n.format("neb.stat.codec", codec), x + 12, y + height - 17, width - 24, LABEL);
        }
    }

    public static void drawUnavailable(FontRenderer font, int x, int y, int width, int height, String title,
        String message) {
        drawPanel(x, y, width, height, 0xFF536575);
        font.drawString(title, x + 12, y + 9, TITLE);
        drawSingleLine(font, message, x + 12, y + 38, width - 24, LABEL);
    }

    private static void drawDirection(FontRenderer font, int x, int y, int width, String direction, long raw, long wire,
        double rawRate, double wireRate, long frames, long packets) {
        font.drawString(direction, x, y, LABEL);
        String total = I18n
            .format("neb.stat.total", ByteSize.format(raw), ByteSize.format(wire), ByteSize.formatRatio(raw, wire));
        drawSingleLine(font, total, x + 8, y + 11, width - 8, VALUE);
        String rate = I18n.format("neb.stat.rate", ByteSize.formatRate(rawRate), ByteSize.formatRate(wireRate));
        drawSingleLine(font, rate, x + 8, y + 23, width - 8, LABEL);
        String detail = I18n.format("neb.stat.frames", Long.toString(frames), Long.toString(packets));
        drawSingleLine(font, detail, x + 8, y + 35, width - 8, LABEL);
    }

    private static void drawPanel(int x, int y, int width, int height, int accent) {
        Gui.drawRect(x, y, x + width, y + height, PANEL_BACKGROUND);
        Gui.drawRect(x, y, x + width, y + 1, PANEL_EDGE);
        Gui.drawRect(x, y, x + 3, y + height, accent);
        Gui.drawRect(x, y + height - 1, x + width, y + height, 0xFF1B252C);
    }

    private static void drawSingleLine(FontRenderer font, String text, int x, int y, int width, int color) {
        font.drawString(font.trimStringToWidth(text, Math.max(1, width)), x, y, color);
    }

    private NebStatRenderer() {}
}
