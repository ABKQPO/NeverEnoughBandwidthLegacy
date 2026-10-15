package cn.ussshenzhou.notenoughbandwidth.client;

import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import cn.ussshenzhou.notenoughbandwidth.network.NebNetwork;
import cn.ussshenzhou.notenoughbandwidth.session.NebSession;
import cn.ussshenzhou.notenoughbandwidth.session.NebSessionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.RemoteStats;
import cn.ussshenzhou.notenoughbandwidth.stat.TrafficSnapshot;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;

public class NebStatScreen extends GuiScreen {

    private static final int QUERY = 0;
    private static final int DONE = 1;
    private TrafficSnapshot local = TrafficSnapshot.EMPTY;
    private TrafficSnapshot remote = TrafficSnapshot.EMPTY;
    private boolean requested;

    @Override
    public void initGui() {
        buttonList.clear();
        buttonList.add(new GuiButton(QUERY, width / 2 - 155, height - 28, 150, 20, I18n.format("neb.stat.query")));
        buttonList.add(new GuiButton(DONE, width / 2 + 5, height - 28, 150, 20, I18n.format("gui.done")));
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
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, I18n.format("neb.stat.title"), width / 2, 12, 0xFFFFFF);
        int top = 34;
        drawSection("neb.stat.client", local, top);
        top += 72;
        if (RemoteStats.present()) {
            drawSection("neb.stat.server", remote, top);
            drawLine(I18n.format("neb.stat.snapshot"), top + 60);
        } else {
            drawLine(I18n.format("neb.stat.server"), top);
            drawLine(I18n.format(requested ? "neb.stat.waiting" : "neb.stat.unavailable"), top + 14);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawSection(String title, TrafficSnapshot snapshot, int y) {
        drawLine(I18n.format(title), y);
        drawDirection(
            "neb.stat.inbound",
            snapshot.inboundRawBytes(),
            snapshot.inboundWireBytes(),
            snapshot.inboundRawRate(),
            snapshot.inboundWireRate(),
            y + 14);
        drawDirection(
            "neb.stat.outbound",
            snapshot.outboundRawBytes(),
            snapshot.outboundWireBytes(),
            snapshot.outboundRawRate(),
            snapshot.outboundWireRate(),
            y + 38);
    }

    private void drawDirection(String direction, long raw, long wire, double rawRate, double wireRate, int y) {
        String ratio = raw == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0D * wire / raw);
        drawLine(
            I18n.format("neb.stat.total", I18n.format(direction), ByteSize.format(raw), ByteSize.format(wire), ratio),
            y);
        drawLine(I18n.format("neb.stat.rate", ByteSize.formatRate(rawRate), ByteSize.formatRate(wireRate)), y + 10);
    }

    private void drawLine(String text, int y) {
        drawString(fontRendererObj, fontRendererObj.trimStringToWidth(text, width - 24), 12, y, 0xFFFFFF);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
