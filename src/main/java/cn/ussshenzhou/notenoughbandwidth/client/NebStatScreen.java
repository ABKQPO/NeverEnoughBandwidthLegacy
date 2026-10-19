package cn.ussshenzhou.notenoughbandwidth.client;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.network.NebNetwork;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import cn.ussshenzhou.notenoughbandwidth.session.NebSessionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.NebStats;
import cn.ussshenzhou.notenoughbandwidth.stat.RemoteStats;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficPoint;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;

public class NebStatScreen extends GuiScreen {

    private static final int QUERY = 0;
    private static final int POLL = 1;
    private static final int DONE = 2;
    private static final long SERVER_POLL_MILLIS = 1000L;
    private static final int MARGIN = 10;
    private static final int CONTENT_TOP = 35;
    private static final int FOOTER_SPACE = 42;
    private TrafficSnapshot local = TrafficSnapshot.EMPTY;
    private TrafficSnapshot remote = TrafficSnapshot.EMPTY;
    private boolean requested;
    private boolean polling;
    private long nextPollAt;

    @Override
    public void initGui() {
        buttonList.clear();
        int buttonWidth = 125;
        int gap = 8;
        int totalWidth = buttonWidth * 3 + gap * 2;
        int startX = Math.max(MARGIN, (width - totalWidth) / 2);
        int y = height - 28;
        buttonList.add(new GuiButton(QUERY, startX, y, buttonWidth, 20, I18n.format("neb.stat.query")));
        buttonList.add(new GuiButton(POLL, startX + buttonWidth + gap, y, buttonWidth, 20, pollLabel()));
        buttonList
            .add(new GuiButton(DONE, startX + (buttonWidth + gap) * 2, y, buttonWidth, 20, I18n.format("gui.done")));
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
        NebStatRenderer.drawHeader(fontRendererObj, width, headerStatus());
        int contentBottom = Math.max(CONTENT_TOP + 120, height - FOOTER_SPACE);
        boolean wide = width >= 600;
        int leftWidth = wide ? Math.min(350, Math.max(300, width / 2 - 16)) : width - MARGIN * 2;
        int chartX = wide ? leftWidth + 18 : MARGIN;
        int chartWidth = wide ? width - chartX - MARGIN : width - MARGIN * 2;
        int columnSpace = contentBottom - CONTENT_TOP;
        int cardHeight = wide ? Math.max(132, (columnSpace - 8) / 2) : Math.min(150, columnSpace);
        int serverCardTop = CONTENT_TOP + cardHeight + 8;
        int serverCardHeight = wide ? cardHeight : Math.min(cardHeight, contentBottom - serverCardTop);
        if (cardHeight >= 132) {
            NebStatRenderer.drawCard(
                fontRendererObj,
                MARGIN,
                CONTENT_TOP,
                leftWidth,
                cardHeight,
                I18n.format("neb.stat.client"),
                local,
                localCodec(session()));
        }

        if (serverCardHeight >= 110) {
            if (RemoteStats.present()) {
                NebStatRenderer.drawCard(
                    fontRendererObj,
                    MARGIN,
                    serverCardTop,
                    leftWidth,
                    serverCardHeight,
                    I18n.format("neb.stat.server"),
                    remote,
                    RemoteStats.codec() + " | " + I18n.format("neb.stat.updated", formatAge(RemoteStats.receivedAt())));
            } else {
                NebStatRenderer.drawUnavailable(
                    fontRendererObj,
                    MARGIN,
                    serverCardTop,
                    leftWidth,
                    serverCardHeight,
                    I18n.format("neb.stat.server"),
                    I18n.format(requested ? "neb.stat.waiting" : "neb.stat.unavailable"));
            }
        }

        int chartTop = wide ? CONTENT_TOP : serverCardTop + serverCardHeight + 8;
        int chartSpace = contentBottom - chartTop;
        int chartHeight = Math.max(60, (chartSpace - 8) / 2);
        if (chartWidth >= 180 && chartSpace >= 120) {
            List<TrafficPoint> clientPoints = NebStats.timeline()
                .snapshot();
            NebChartRenderer.draw(
                fontRendererObj,
                chartX,
                chartTop,
                chartWidth,
                chartHeight,
                I18n.format("neb.stat.chart.client"),
                clientPoints,
                mouseX,
                mouseY);
            NebChartRenderer.draw(
                fontRendererObj,
                chartX,
                chartTop + chartHeight + 8,
                chartWidth,
                chartHeight,
                I18n.format("neb.stat.chart.server"),
                RemoteStats.timeline(),
                mouseX,
                mouseY);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
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

    private String headerStatus() {
        if (polling) {
            return "neb.stat.status.polling";
        }
        return RemoteStats.present() ? "neb.stat.status.connected" : "neb.stat.status.idle";
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
