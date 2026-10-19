package cn.ussshenzhou.notenoughbandwidth.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;

import org.lwjgl.opengl.GL11;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.network.NebNetwork;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import cn.ussshenzhou.notenoughbandwidth.session.NebSessionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.NebStats;
import cn.ussshenzhou.notenoughbandwidth.stat.RemoteStats;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficPoint;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;

public class NebStatScreen extends GuiScreen {

    private static final int QUERY = 0;
    private static final int POLL = 1;
    private static final int DONE = 2;
    private static final long SERVER_POLL_MILLIS = 1000L;
    private TrafficSnapshot local = TrafficSnapshot.EMPTY;
    private TrafficSnapshot remote = TrafficSnapshot.EMPTY;
    private boolean requested;
    private boolean polling;
    private long nextPollAt;

    @Override
    public void initGui() {
        buttonList.clear();
        buttonList.add(new GuiButton(QUERY, width / 2 - 195, height - 28, 125, 20, I18n.format("neb.stat.query")));
        buttonList.add(new GuiButton(POLL, width / 2 - 62, height - 28, 125, 20, pollLabel()));
        buttonList.add(new GuiButton(DONE, width / 2 + 71, height - 28, 125, 20, I18n.format("gui.done")));
        updateScreen();
    }

    @Override
    public void updateScreen() {
        NebSession session = mc.getNetHandler() == null ? null
            : NebSessionRegistry.of(
                mc.getNetHandler()
                    .getNetworkManager());
        local = session == null ? TrafficSnapshot.EMPTY : TrafficSnapshot.of(session.stats());
        remote = RemoteStats.snapshot();
        if (polling && System.currentTimeMillis() >= nextPollAt) {
            requestServer();
            nextPollAt = System.currentTimeMillis() + SERVER_POLL_MILLIS;
        }
        if (mc.theWorld == null) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == DONE) {
            mc.displayGuiScreen(null);
        } else if (button.id == QUERY && mc.getNetHandler() != null) {
            NebSession session = NebSessionRegistry.of(
                mc.getNetHandler()
                    .getNetworkManager());
            if (session != null && session.outboundEnabled()) {
                NebNetwork.queryServer();
                requested = true;
            }
        } else if (button.id == POLL) {
            polling = !polling;
            button.displayString = pollLabel();
            if (polling) {
                requestServer();
                nextPollAt = System.currentTimeMillis() + SERVER_POLL_MILLIS;
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format("neb.stat.title"), width / 2, 12, 0xFFFFFF);
        int chartX = Math.max(width / 2, 220);
        int textWidth = chartX - 24;
        drawSection("neb.stat.client", local, 34, textWidth, localCodec(session()));
        int top = 112;
        if (RemoteStats.present()) {
            drawSection("neb.stat.server", remote, top, textWidth, RemoteStats.codec());
            drawLine(I18n.format("neb.stat.updated", formatAge(RemoteStats.receivedAt())), top + 76, textWidth);
        } else {
            drawLine(I18n.format("neb.stat.server"), top, textWidth);
            drawLine(I18n.format(requested ? "neb.stat.waiting" : "neb.stat.unavailable"), top + 14, textWidth);
        }
        int chartWidth = width - chartX - 12;
        int chartHeight = Math.max(48, (height - 82) / 2);
        if (chartWidth >= 120) {
            drawChart(
                chartX,
                32,
                chartWidth,
                chartHeight,
                "neb.stat.chart.client",
                NebStats.timeline()
                    .snapshot());
            drawChart(
                chartX,
                40 + chartHeight,
                chartWidth,
                chartHeight,
                "neb.stat.chart.server",
                RemoteStats.timeline());
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawSection(String title, TrafficSnapshot snapshot, int y, int maxWidth, String codec) {
        drawLine(I18n.format(title), y, maxWidth);
        drawDirection(
            "neb.stat.inbound",
            snapshot.inboundRawBytes(),
            snapshot.inboundWireBytes(),
            snapshot.inboundRawRate(),
            snapshot.inboundWireRate(),
            y + 14,
            maxWidth);
        drawDirection(
            "neb.stat.outbound",
            snapshot.outboundRawBytes(),
            snapshot.outboundWireBytes(),
            snapshot.outboundRawRate(),
            snapshot.outboundWireRate(),
            y + 38,
            maxWidth);
        if (codec != null && !codec.isEmpty()) {
            drawLine(I18n.format("neb.stat.codec", codec), y + 62, maxWidth);
        }
    }

    private void drawDirection(String direction, long raw, long wire, double rawRate, double wireRate, int y,
        int maxWidth) {
        String ratio = raw == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0D * wire / raw);
        drawLine(
            I18n.format("neb.stat.total", I18n.format(direction), ByteSize.format(raw), ByteSize.format(wire), ratio),
            y,
            maxWidth);
        drawLine(
            I18n.format("neb.stat.rate", ByteSize.formatRate(rawRate), ByteSize.formatRate(wireRate)),
            y + 10,
            maxWidth);
    }

    private void drawLine(String text, int y, int maxWidth) {
        drawString(fontRendererObj, fontRendererObj.trimStringToWidth(text, maxWidth), 12, y, 0xFFFFFF);
    }

    private void drawChart(int x, int y, int chartWidth, int chartHeight, String title, List<TrafficPoint> points) {
        drawRect(x, y, x + chartWidth, y + chartHeight, 0x66000000);
        drawString(fontRendererObj, I18n.format(title), x + 4, y + 4, 0xFFFFFF);
        drawString(fontRendererObj, I18n.format("neb.stat.legend.raw"), x + 128, y + 4, 0xFF55FFFF);
        drawString(fontRendererObj, I18n.format("neb.stat.legend.wire"), x + 192, y + 4, 0xFF5599FF);
        if (points.size() < 2) {
            return;
        }
        int plotX = x + 4;
        int plotY = y + 18;
        int plotWidth = Math.max(1, chartWidth - 8);
        int plotHeight = Math.max(1, chartHeight - 22);
        long maximum = maximum(points);
        drawHorizontalLine(plotX, plotX + plotWidth, plotY + plotHeight, 0x55666666);
        drawHorizontalLine(plotX, plotX + plotWidth, plotY + plotHeight / 2, 0x33444444);
        drawSeries(points, plotX, plotY, plotWidth, plotHeight, maximum, false, 0xFF55FFFF);
        drawSeries(points, plotX, plotY, plotWidth, plotHeight, maximum, true, 0xFF5599FF);
    }

    private void drawSeries(List<TrafficPoint> points, int x, int y, int width, int height, long maximum, boolean wire,
        int color) {
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f((color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F, 1.0F);
        GL11.glLineWidth(1.0F);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_LINE_STRIP);
        for (int i = 0; i < points.size(); i++) {
            TrafficPoint point = points.get(i);
            long value;
            value = wire ? sum(point.inboundWireBytes(), point.outboundWireBytes())
                : sum(point.inboundRawBytes(), point.outboundRawBytes());
            double fraction = maximum == 0 ? 0.0D : Math.min(1.0D, (double) value / maximum);
            double pointX = x + (double) i * width / Math.max(1, points.size() - 1);
            double pointY = y + height - fraction * height;
            tessellator.addVertex(pointX, pointY, 0.0D);
        }
        tessellator.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    private static long maximum(List<TrafficPoint> points) {
        long maximum = 0;
        for (TrafficPoint point : points) {
            maximum = Math.max(maximum, sum(point.inboundRawBytes(), point.outboundRawBytes()));
            maximum = Math.max(maximum, sum(point.inboundWireBytes(), point.outboundWireBytes()));
        }
        return maximum;
    }

    private static long sum(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private void requestServer() {
        if (mc.getNetHandler() == null) {
            return;
        }
        NebSession session = NebSessionRegistry.of(
            mc.getNetHandler()
                .getNetworkManager());
        if (session != null && session.outboundEnabled()) {
            NebNetwork.queryServer();
            requested = true;
        }
    }

    private NebSession session() {
        return mc.getNetHandler() == null ? null
            : NebSessionRegistry.of(
                mc.getNetHandler()
                    .getNetworkManager());
    }

    private static String localCodec(NebSession session) {
        if (session == null || !session.outboundEnabled()) {
            return I18n.format("neb.stat.unnegotiated");
        }
        StringBuilder dictionaries = new StringBuilder();
        appendDictionary(dictionaries, "out", session.outboundDictionaryVersion(), session.outboundDictionarySize());
        appendDictionary(dictionaries, "in", session.inboundDictionaryVersion(), session.inboundDictionarySize());
        return session.outboundType()
            .configName() + " | "
            + PacketCodecRegistry.describe(session.outboundType())
            + " | aggregation "
            + NebConfig.aggregationWindowMillis
            + " ms"
            + dictionaries;
    }

    private static void appendDictionary(StringBuilder target, String direction, int version, int size) {
        if (version != 0) {
            target.append(" | dictionary ")
                .append(direction)
                .append(" v")
                .append(version)
                .append(" (")
                .append(size)
                .append(" bytes)");
        }
    }

    private String pollLabel() {
        return I18n.format(polling ? "neb.stat.poll.stop" : "neb.stat.poll.start");
    }

    private static String formatAge(long receivedAt) {
        long age = Math.max(0L, System.currentTimeMillis() - receivedAt);
        return I18n.format("neb.stat.age", age / 1000.0D);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
