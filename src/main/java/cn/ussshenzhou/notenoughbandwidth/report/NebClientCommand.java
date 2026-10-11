package cn.ussshenzhou.notenoughbandwidth.report;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.network.NebNetwork;
import cn.ussshenzhou.notenoughbandwidth.stat.NebStats;
import cn.ussshenzhou.notenoughbandwidth.stat.RemoteStats;

/**
 * Client side counterpart of {@link NebCommand}, so a player can measure their own link without
 * server access.
 *
 * <pre>
 * /nebclient stat     this client's own summary, plus the server's if it has answered
 * /nebclient report   writes an HTML report and two CSV files under neb-reports/
 * /nebclient reset    zeroes this client's counters
 * /nebclient server   asks the server for its numbers; needs operator rights there
 * </pre>
 *
 * @author USS_Shenzhou
 */
public class NebClientCommand extends CommandBase {

    private static final String REPORT_DIRECTORY = "neb-reports";
    private static final int CHAT_KINDS = 8;
    private static final List<String> SUBCOMMANDS = Arrays.asList("stat", "report", "reset", "server");

    @Override
    public String getCommandName() {
        return "nebclient";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/nebclient <stat|report|reset|server>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, SUBCOMMANDS.toArray(new String[0])) : null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String action = args.length == 0 ? "stat" : args[0].toLowerCase();
        switch (action) {
            case "stat" -> stat(sender);
            case "report" -> report(sender);
            case "reset" -> reset(sender);
            case "server" -> queryServer(sender);
            default -> reply(sender, EnumChatFormatting.RED + getCommandUsage(sender));
        }
    }

    private void stat(ICommandSender sender) {
        ReportSnapshot snapshot = ReportBuilder.capture("client");
        List<String> lines = new ArrayList<>(
            StatLines.summary(
                "NEB client traffic",
                snapshot.traffic(),
                snapshot.codec(),
                snapshot.sessions()
                    .size()));
        lines.addAll(StatLines.topKinds("Heaviest incoming kinds", snapshot.inbound(), CHAT_KINDS));
        if (RemoteStats.present()) {
            lines.addAll(StatLines.summary("NEB server traffic", RemoteStats.snapshot(), RemoteStats.codec(), 0));
        } else {
            lines.add(EnumChatFormatting.GRAY + "Run /nebclient server for the server's own numbers.");
        }
        for (String line : lines) {
            reply(sender, line);
        }
    }

    private void report(ICommandSender sender) {
        ReportSnapshot snapshot = ReportBuilder.capture("client");
        try {
            Path[] written = ReportExporter.export(snapshot, reportDirectory());
            reply(sender, EnumChatFormatting.GREEN + "NEB report written:");
            for (Path path : written) {
                reply(sender, EnumChatFormatting.GRAY + "  " + path.toAbsolutePath());
            }
        } catch (IOException e) {
            NebLog.error("Failed to write the report.", e);
            reply(sender, EnumChatFormatting.RED + "Failed to write the report: " + e.getMessage());
        }
    }

    private void reset(ICommandSender sender) {
        NebStats.reset();
        RemoteStats.clear();
        reply(sender, EnumChatFormatting.GREEN + "NEB client counters reset.");
    }

    private void queryServer(ICommandSender sender) {
        NebNetwork.queryServer();
        reply(sender, EnumChatFormatting.GRAY + "Asked the server for its numbers; run /nebclient stat to see them.");
    }

    private static Path reportDirectory() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft == null || minecraft.mcDataDir == null ? Paths.get(REPORT_DIRECTORY)
            : minecraft.mcDataDir.toPath()
                .resolve(REPORT_DIRECTORY);
    }

    private static void reply(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(message));
    }
}
