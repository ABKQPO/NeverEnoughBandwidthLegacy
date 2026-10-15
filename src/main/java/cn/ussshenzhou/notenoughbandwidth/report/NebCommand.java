package cn.ussshenzhou.notenoughbandwidth.report;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.stat.NebStats;

public class NebCommand extends CommandBase {

    private static final String REPORT_DIRECTORY = "neb-reports";
    private static final int CHAT_KINDS = 8;
    private static final List<String> SUBCOMMANDS = Arrays.asList("stat", "report", "reset", "codec");

    @Override
    public String getCommandName() {
        return "neb";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/neb <stat|report|reset|codec>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
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
            case "codec" -> codec(sender);
            default -> reply(sender, EnumChatFormatting.RED + getCommandUsage(sender));
        }
    }

    private void stat(ICommandSender sender) {
        ReportSnapshot snapshot = ReportBuilder.capture("server");
        List<String> lines = new ArrayList<>(
            StatLines.summary(
                "NEB server traffic",
                snapshot.traffic(),
                snapshot.codec(),
                snapshot.sessions()
                    .size()));
        lines.addAll(StatLines.topKinds("Heaviest outgoing kinds", snapshot.outbound(), CHAT_KINDS));
        lines.addAll(StatLines.topKinds("Heaviest incoming kinds", snapshot.inbound(), CHAT_KINDS));
        for (String line : lines) {
            reply(sender, line);
        }
    }

    private void report(ICommandSender sender) {
        ReportSnapshot snapshot = ReportBuilder.capture("server");
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
        reply(sender, EnumChatFormatting.GREEN + "NEB counters reset.");
    }

    private void codec(ICommandSender sender) {
        PacketCodecType preferred = PacketCodecRegistry.preferred();
        reply(sender, EnumChatFormatting.AQUA + "NEB backends");
        for (PacketCodecType type : PacketCodecRegistry.available()) {
            reply(
                sender,
                (type == preferred ? EnumChatFormatting.GREEN + "  * " : EnumChatFormatting.GRAY + "    ") + type
                    .configName() + EnumChatFormatting.GRAY + " - " + PacketCodecRegistry.describe(type));
        }
    }

    private static Path reportDirectory() {
        MinecraftServer server = MinecraftServer.getServer();
        return server == null ? Paths.get(REPORT_DIRECTORY)
            : server.getFile(REPORT_DIRECTORY)
                .toPath();
    }

    private static void reply(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(message));
    }
}
