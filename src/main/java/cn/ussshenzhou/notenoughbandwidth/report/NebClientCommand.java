package cn.ussshenzhou.notenoughbandwidth.report;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import cn.ussshenzhou.notenoughbandwidth.NebLog;
import cn.ussshenzhou.notenoughbandwidth.capture.DatasetRecorder;
import cn.ussshenzhou.notenoughbandwidth.capture.DatasetStatus;
import cn.ussshenzhou.notenoughbandwidth.config.NebConfig;
import cn.ussshenzhou.notenoughbandwidth.network.NebNetwork;
import cn.ussshenzhou.notenoughbandwidth.stat.NebStats;
import cn.ussshenzhou.notenoughbandwidth.stat.RemoteStats;
import cn.ussshenzhou.notenoughbandwidth.util.ByteSize;

public class NebClientCommand extends CommandBase {

    private static final String REPORT_DIRECTORY = "neb-reports";
    private static final int CHAT_KINDS = 8;
    private static final List<String> SUBCOMMANDS = Arrays.asList("stat", "report", "reset", "server", "capture");
    private static final List<String> CAPTURE_SUBCOMMANDS = Arrays.asList("start", "stop", "status");

    @Override
    public String getCommandName() {
        return "nebclient";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/nebclient <stat|report|reset|server|capture>";
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
            case "server" -> queryServer(sender);
            case "capture" -> capture(sender, args);
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

    private void capture(ICommandSender sender, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        DatasetRecorder recorder = DatasetRecorder.global();
        try {
            switch (action) {
                case "start" -> {
                    long seconds = args.length >= 3 ? positive(args[2], "seconds") : NebConfig.datasetCaptureSeconds;
                    long mebibytes = args.length >= 4 ? positive(args[3], "MiB") : NebConfig.datasetCaptureMiB;
                    DatasetStatus status = recorder.start(captureDirectory(), "client", seconds, mebibytes);
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
                default -> reply(
                    sender,
                    EnumChatFormatting.RED + "/nebclient capture <start [seconds] [MiB]|stop|status>");
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

    private static Path captureDirectory() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft == null || minecraft.mcDataDir == null ? Paths.get("neb-datasets")
            : minecraft.mcDataDir.toPath()
                .resolve("neb-datasets");
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
