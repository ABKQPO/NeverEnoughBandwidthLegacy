package cn.ussshenzhou.notenoughbandwidth.client;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;

import org.lwjgl.opengl.GL11;

import cn.ussshenzhou.notenoughbandwidth.stat.TrafficPoint;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;

public class NebChartRenderer {

    private static final int PANEL_BACKGROUND = 0xB010171E;
    private static final int PANEL_EDGE = 0xFF2C3E49;
    private static final int GRID = 0x55445A65;
    private static final int LABEL = 0xFF91A5B1;
    private static final int TITLE = 0xFFEAF4F8;
    private static final int RAW_COLOR = 0xFF66E0E8;
    private static final int WIRE_COLOR = 0xFF6EA8FF;
    private static final int RAW_FILL = 0x2266E0E8;
    private static final int WIRE_FILL = 0x226EA8FF;
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    public static void draw(FontRenderer font, int x, int y, int width, int height, String title,
        List<TrafficPoint> points, int mouseX, int mouseY) {
        if (width < 96 || height < 52) {
            return;
        }
        Gui.drawRect(x, y, x + width, y + height, PANEL_BACKGROUND);
        Gui.drawRect(x, y, x + width, y + 1, PANEL_EDGE);
        Gui.drawRect(x, y, x + 2, y + height, WIRE_COLOR);
        font.drawString(title, x + 10, y + 7, TITLE);

        long maximum = maximum(points);
        long scale = Math.max(1L, maximum);
        int axisLabelWidth = axisLabelWidth(font, scale);
        int plotLeft = x + 10 + axisLabelWidth + 6;
        int plotTop = y + 28;
        int plotRight = x + width - 9;
        int plotBottom = y + height - 27;
        if (plotRight <= plotLeft || plotBottom <= plotTop) {
            return;
        }

        TrafficPoint latest = points.isEmpty() ? null : points.get(points.size() - 1);
        drawLegend(font, x + 10, y + 18, latest, plotRight - x - 10);
        if (points.size() < 2) {
            drawTimeAxis(font, points, plotLeft, plotRight, plotBottom);
            font.drawString(I18n.format("neb.stat.chart.empty"), plotLeft, plotTop + 8, LABEL);
            return;
        }

        drawGrid(font, x + 8, plotLeft, plotTop, plotRight, plotBottom, scale);
        drawSeries(points, plotLeft, plotTop, plotRight - plotLeft, plotBottom - plotTop, scale, false);
        drawSeries(points, plotLeft, plotTop, plotRight - plotLeft, plotBottom - plotTop, scale, true);
        drawTimeAxis(font, points, plotLeft, plotRight, plotBottom);
        drawCursorAndTooltip(font, points, plotLeft, plotTop, plotRight, plotBottom, mouseX, mouseY);
    }

    private static void drawLegend(FontRenderer font, int x, int y, TrafficPoint latest, int availableWidth) {
        String raw = I18n.format("neb.stat.legend.raw") + " "
            + ByteSize.formatRate(latest == null ? 0 : value(latest, false));
        String wire = I18n.format("neb.stat.legend.wire") + " "
            + ByteSize.formatRate(latest == null ? 0 : value(latest, true));
        int wireX = x + Math.max(64, availableWidth / 2);
        font.drawString(raw, x, y, RAW_COLOR);
        font.drawString(wire, wireX, y, WIRE_COLOR);
    }

    private static void drawGrid(FontRenderer font, int labelLeft, int left, int top, int right, int bottom,
        long maximum) {
        for (int tick = 0; tick <= 3; tick++) {
            int lineY = bottom - (bottom - top) * tick / 3;
            Gui.drawRect(left, lineY, right, lineY + 1, GRID);
            long value = tickValue(maximum, tick);
            String label = ByteSize.formatRate(value);
            int labelX = Math.max(labelLeft, left - font.getStringWidth(label) - 5);
            font.drawString(label, labelX, lineY - 4, LABEL);
        }
    }

    private static int axisLabelWidth(FontRenderer font, long maximum) {
        int width = 0;
        for (int tick = 0; tick <= 3; tick++) {
            width = Math.max(width, font.getStringWidth(ByteSize.formatRate(tickValue(maximum, tick))));
        }
        return width;
    }

    private static void drawTimeAxis(FontRenderer font, List<TrafficPoint> points, int left, int right, int bottom) {
        if (points.isEmpty()) {
            return;
        }
        int last = points.size() - 1;
        int middle = last / 2;
        int[] indices = right - left < 160 ? new int[] { 0, last } : new int[] { 0, middle, last };
        int previousRight = Integer.MIN_VALUE;
        for (int index : indices) {
            String label = formatTime(
                points.get(index)
                    .timestampMillis());
            int labelWidth = font.getStringWidth(label);
            int center = left + index * (right - left) / Math.max(1, last);
            int labelX = Math.max(left, Math.min(right - labelWidth, center - labelWidth / 2));
            if (labelX < previousRight + 4) {
                continue;
            }
            font.drawString(label, labelX, bottom + 5, LABEL);
            previousRight = labelX + labelWidth;
        }
    }

    private static String formatTime(long timestampMillis) {
        synchronized (TIME_FORMAT) {
            return TIME_FORMAT.format(new Date(timestampMillis));
        }
    }

    private static long tickValue(long maximum, int tick) {
        if (tick == 0) {
            return 0L;
        }
        if (tick == 3) {
            return maximum;
        }
        long quotient = maximum / 3L;
        long remainder = maximum % 3L;
        return quotient * tick + remainder * tick / 3L;
    }

    private static void drawSeries(List<TrafficPoint> points, int x, int y, int width, int height, long maximum,
        boolean wire) {
        int color = wire ? WIRE_COLOR : RAW_COLOR;
        int fill = wire ? WIRE_FILL : RAW_FILL;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_LINE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f((color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F, 1.0F);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
        setColor(fill);
        for (int i = 0; i < points.size(); i++) {
            double pointX = x + (double) i * width / Math.max(1, points.size() - 1);
            double pointY = y + height - fraction(points.get(i), wire, maximum) * height;
            tessellator.addVertex(pointX, y + height, 0.0D);
            tessellator.addVertex(pointX, pointY, 0.0D);
        }
        tessellator.draw();
        setColor(color);
        GL11.glColor4f((color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F, 1.0F);
        GL11.glLineWidth(1.6F);
        tessellator.startDrawing(GL11.GL_LINE_STRIP);
        for (int i = 0; i < points.size(); i++) {
            double pointX = x + (double) i * width / Math.max(1, points.size() - 1);
            double pointY = y + height - fraction(points.get(i), wire, maximum) * height;
            tessellator.addVertex(pointX, pointY, 0.0D);
        }
        tessellator.draw();
        GL11.glPopAttrib();
    }

    private static void drawCursorAndTooltip(FontRenderer font, List<TrafficPoint> points, int left, int top, int right,
        int bottom, int mouseX, int mouseY) {
        if (mouseX < left || mouseX > right || mouseY < top || mouseY > bottom) {
            return;
        }
        int index = (int) Math.round((double) (mouseX - left) * (points.size() - 1) / Math.max(1, right - left));
        index = Math.max(0, Math.min(points.size() - 1, index));
        int cursorX = left + index * (right - left) / Math.max(1, points.size() - 1);
        Gui.drawRect(cursorX, top, cursorX + 1, bottom, 0x667D9BAA);

        TrafficPoint point = points.get(index);
        long raw = value(point, false);
        long wire = value(point, true);
        String[] lines = { I18n.format("neb.stat.chart.sample", index + 1, points.size()),
            I18n.format("neb.stat.chart.age", ageSeconds(point)),
            I18n.format("neb.stat.chart.raw", ByteSize.formatRate(raw)),
            I18n.format("neb.stat.chart.wire", ByteSize.formatRate(wire)),
            I18n.format("neb.stat.chart.ratio", ByteSize.formatRatio(raw, wire)) };
        int tooltipWidth = 0;
        for (String line : lines) {
            tooltipWidth = Math.max(tooltipWidth, font.getStringWidth(line));
        }
        tooltipWidth += 12;
        int tooltipHeight = lines.length * 10 + 8;
        int tooltipX = mouseX + 10;
        int tooltipY = mouseY + 8;
        if (tooltipX + tooltipWidth > right + 1) {
            tooltipX = mouseX - tooltipWidth - 10;
        }
        if (tooltipY + tooltipHeight > bottom + 1) {
            tooltipY = bottom - tooltipHeight;
        }
        Gui.drawRect(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + tooltipHeight, 0xEF101820);
        Gui.drawRect(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + 1, PANEL_EDGE);
        for (int i = 0; i < lines.length; i++) {
            font.drawString(
                lines[i],
                tooltipX + 6,
                tooltipY + 5 + i * 10,
                i == 2 ? RAW_COLOR : i == 3 ? WIRE_COLOR : TITLE);
        }
    }

    private static long maximum(List<TrafficPoint> points) {
        long maximum = 0;
        for (TrafficPoint point : points) {
            maximum = Math.max(maximum, value(point, false));
            maximum = Math.max(maximum, value(point, true));
        }
        return maximum;
    }

    private static double fraction(TrafficPoint point, boolean wire, long maximum) {
        return maximum == 0 ? 0.0D : Math.min(1.0D, (double) value(point, wire) / maximum);
    }

    private static long value(TrafficPoint point, boolean wire) {
        return sum(
            wire ? point.inboundWireBytes() : point.inboundRawBytes(),
            wire ? point.outboundWireBytes() : point.outboundRawBytes());
    }

    private static long ageSeconds(TrafficPoint point) {
        return Math.max(0L, System.currentTimeMillis() - point.timestampMillis()) / 1000L;
    }

    private static long sum(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static void setColor(int color) {
        Tessellator.instance.setColorRGBA_F(
            (color >> 16 & 255) / 255.0F,
            (color >> 8 & 255) / 255.0F,
            (color & 255) / 255.0F,
            (color >>> 24 & 255) / 255.0F);
    }

    private NebChartRenderer() {}
}
