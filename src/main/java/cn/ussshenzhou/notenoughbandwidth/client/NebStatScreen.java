package cn.ussshenzhou.notenoughbandwidth.client;

import java.util.Collections;
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
    private static final long REQUEST_TIMEOUT_MILLIS = 2500L;
    private static final int MAX_UNANSWERED_REQUESTS = 3;
    private static final long CHART_REFRESH_MILLIS = 250L;
    private static final int MARGIN = 10;
    private static final int CONTENT_TOP = 35;
    private static final int FOOTER_SPACE = 42;
    private TrafficSnapshot local = TrafficSnapshot.EMPTY;
    private TrafficSnapshot remote = TrafficSnapshot.EMPTY;
    private List<TrafficPoint> clientPoints = Collections.emptyList();
    private List<TrafficPoint> serverPoints = Collections.emptyList();
    private boolean requested;
    private boolean polling;
    private long nextPollAt;
    private long pendingSince;
    private long lastRemoteAt;
    private long chartsRefreshedAt;
    private int unanswered;

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
        NebSession session = session();
        local = session == null ? TrafficSnapshot.EMPTY : TrafficSnapshot.of(session.stats());
        remote = RemoteStats.snapshot();
        long now = System.currentTimeMillis();
        long remoteAt = RemoteStats.receivedAt();
        if (remoteAt != lastRemoteAt) {
            lastRemoteAt = remoteAt;
            pendingSince = 0L;
            unanswered = 0;
        }
        if (polling && now >= nextPollAt) {
            nextPollAt = now + SERVER_POLL_MILLIS;
            if (pendingSince == 0L) {
                requestServer();
            } else if (now - pendingSince >= REQUEST_TIMEOUT_MILLIS) {
                pendingSince = 0L;
                unanswered++;
                if (unanswered >= MAX_UNANSWERED_REQUESTS) {
                    polling = false;
                    refreshPollButton();
                } else {
                    requestServer();
                }
            }
        }
        refreshCharts(now);
        if (mc.theWorld == null) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == DONE) {
            mc.displayGuiScreen(null);
        } else if (button.id == QUERY) {
            requestServer();
        } else if (button.id == POLL) {
            polling = !polling;
            pendingSince = 0L;
            unanswered = 0;
            button.displayString = pollLabel();
            if (polling) {
                nextPollAt = System.currentTimeMillis() + SERVER_POLL_MILLIS;
                requestServer();
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
                    I18n.format(serverCardHint()));
            }
        }

        int chartTop = wide ? CONTENT_TOP : serverCardTop + serverCardHeight + 8;
        int chartSpace = contentBottom - chartTop;
        int chartHeight = Math.max(60, (chartSpace - 8) / 2);
        if (chartWidth >= 180 && chartSpace >= 120) {
            refreshCharts(System.currentTimeMillis());
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
                serverPoints,
                mouseX,
                mouseY);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private boolean requestServer() {
        NebSession session = session();
        if (session == null || !session.channel()
            .isActive()) {
            return false;
        }
        NebNetwork.queryServer();
        requested = true;
        pendingSince = System.currentTimeMillis();
        return true;
    }

    private NebSession session() {
        return mc.getNetHandler() == null ? null
            : NebSessionRegistry.of(
                mc.getNetHandler()
                    .getNetworkManager());
    }

    private void refreshCharts(long now) {
        if (now - chartsRefreshedAt < CHART_REFRESH_MILLIS) {
            return;
        }
        chartsRefreshedAt = now;
        clientPoints = NebStats.timeline()
            .snapshot();
        serverPoints = RemoteStats.timeline();
    }

    private void refreshPollButton() {
        for (Object element : buttonList) {
            if (element instanceof GuiButton button && button.id == POLL) {
                button.displayString = pollLabel();
            }
        }
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

    private String serverCardHint() {
        if (unanswered >= MAX_UNANSWERED_REQUESTS) {
            return "neb.stat.denied";
        }
        return requested ? "neb.stat.waiting" : "neb.stat.unavailable";
    }

    private String headerStatus() {
        if (polling) {
            return "neb.stat.status.polling";
        }
        if (unanswered >= MAX_UNANSWERED_REQUESTS && !RemoteStats.present()) {
            return "neb.stat.status.denied";
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
