package cn.ussshenzhou.notenoughbandwidth.report;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.capture.DatasetRecorder;
import cn.ussshenzhou.notenoughbandwidth.capture.DatasetStatus;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecRegistry;
import cn.ussshenzhou.notenoughbandwidth.codec.PacketCodecType;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.stat.NebStats;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;

public class NebCommand extends CommandBase {

    private static final String REPORT_DIRECTORY = "neb-reports";
    private static final int CHAT_KINDS = 8;
    private static final List<String> SUBCOMMANDS = Arrays.asList("stat", "report", "reset", "codec", "capture");
    private static final List<String> CAPTURE_SUBCOMMANDS = Arrays.asList("start", "stop", "status");

    @Override
    public String getCommandName() {
        return "neb";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/neb <stat|report|reset|codec|capture>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBCOMMANDS.toArray(new String[0]));
        }
        return args.length == 2 && "capture".equalsIgnoreCase(args[0])
            ? getListOfStringsMatchingLastWord(args, CAPTURE_SUBCOMMANDS.toArray(new String[0]))
            : null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String action = args.length == 0 ? "stat" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "stat" -> stat(sender);
            case "report" -> report(sender);
            case "reset" -> reset(sender);
            case "codec" -> codec(sender);
            case "capture" -> capture(sender, args);
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

    private void capture(ICommandSender sender, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        DatasetRecorder recorder = DatasetRecorder.global();
        try {
            switch (action) {
                case "start" -> {
                    long seconds = args.length >= 3 ? positive(args[2], "seconds") : NebConfig.datasetCaptureSeconds;
                    long mebibytes = args.length >= 4 ? positive(args[3], "MiB") : NebConfig.datasetCaptureMiB;
                    DatasetStatus status = recorder.start(captureDirectory(), "server", seconds, mebibytes);
                    reply(
                        sender,
                        EnumChatFormatting.GREEN + "NEB dataset capture started: "
                            + status.dataset()
                                .toAbsolutePath());
                }
                case "stop" -> {
                    recorder.stop();
                    reply(sender, EnumChatFormatting.GRAY + "NEB dataset capture is finishing asynchronously.");
                }
                case "status" -> captureStatus(sender, recorder.status());
                default -> reply(sender, EnumChatFormatting.RED + "/neb capture <start [seconds] [MiB]|stop|status>");
            }
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            reply(sender, EnumChatFormatting.RED + "Capture failed: " + e.getMessage());
        }
    }

    private static void captureStatus(ICommandSender sender, DatasetStatus status) {
        String state = status.active() ? "active" : status.stopping() ? "finishing" : "idle";
        reply(
            sender,
            EnumChatFormatting.AQUA + "NEB dataset capture: "
                + state
                + ", "
                + status.acceptedRecords()
                + " records, "
                + ByteSize.format(status.acceptedBytes())
                + " accepted, "
                + status.droppedRecords()
                + " dropped.");
        if (status.dataset() != null) {
            reply(
                sender,
                EnumChatFormatting.GRAY + "  dataset: "
                    + status.dataset()
                        .toAbsolutePath());
        }
        if (status.analysis() != null && !status.active() && !status.stopping()) {
            reply(
                sender,
                EnumChatFormatting.GRAY + "  analysis: "
                    + status.analysis()
                        .toAbsolutePath());
        }
    }

    private static long positive(String value, String label) {
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a positive integer");
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(label + " must be a positive integer");
        }
        return parsed;
    }

    private static Path reportDirectory() {
        MinecraftServer server = MinecraftServer.getServer();
        return server == null ? Paths.get(REPORT_DIRECTORY)
            : server.getFile(REPORT_DIRECTORY)
                .toPath();
    }

    private static Path captureDirectory() {
        MinecraftServer server = MinecraftServer.getServer();
        return server == null ? Paths.get("neb-datasets")
            : server.getFile("neb-datasets")
                .toPath();
    }

    private static void reply(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(message));
    }
}
