package cn.ussshenzhou.notenoughbandwidth.report;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Writes a {@link ReportSnapshot} to disk as one HTML file to look at and two CSV files to analyse.
 * <p>
 * Everything is UTF-8 and self-contained, so a report can be moved off the machine that produced
 * it without losing anything.
 *
 * @author USS_Shenzhou
 */
public class ReportExporter {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * @return the paths written, HTML first
     */
    public static Path[] export(ReportSnapshot snapshot, Path directory) throws IOException {
        Files.createDirectories(directory);
        String stem = "neb-report-" + snapshot.side()
            + '-'
            + FILE_STAMP.format(
                Instant.ofEpochMilli(snapshot.takenAtMillis())
                    .atZone(ZoneId.systemDefault()));
        Path html = directory.resolve(stem + ".html");
        Path traffic = directory.resolve(stem + "-traffic.csv");
        Path packets = directory.resolve(stem + "-packets.csv");

        try (Writer writer = open(html)) {
            ReportHtmlWriter.write(writer, snapshot);
        }
        try (Writer writer = open(traffic)) {
            ReportCsvWriter.writeTraffic(writer, snapshot);
        }
        try (Writer writer = open(packets)) {
            ReportCsvWriter.writePackets(writer, snapshot);
        }
        return new Path[] { html, traffic, packets };
    }

    private static Writer open(Path path) throws IOException {
        return new BufferedWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
    }

    private ReportExporter() {}
}
