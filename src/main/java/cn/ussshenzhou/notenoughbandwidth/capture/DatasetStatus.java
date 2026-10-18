package cn.ussshenzhou.notenoughbandwidth.capture;

import java.nio.file.Path;

public class DatasetStatus {

    private final boolean active;
    private final boolean stopping;
    private final Path dataset;
    private final Path analysis;
    private final long startedAtMillis;
    private final long elapsedMillis;
    private final long acceptedRecords;
    private final long acceptedBytes;
    private final long droppedRecords;
    private final long limitBytes;
    private final long limitMillis;

    public DatasetStatus(boolean active, boolean stopping, Path dataset, Path analysis, long startedAtMillis,
        long elapsedMillis, long acceptedRecords, long acceptedBytes, long droppedRecords, long limitBytes,
        long limitMillis) {
        this.active = active;
        this.stopping = stopping;
        this.dataset = dataset;
        this.analysis = analysis;
        this.startedAtMillis = startedAtMillis;
        this.elapsedMillis = elapsedMillis;
        this.acceptedRecords = acceptedRecords;
        this.acceptedBytes = acceptedBytes;
        this.droppedRecords = droppedRecords;
        this.limitBytes = limitBytes;
        this.limitMillis = limitMillis;
    }

    public boolean active() {
        return active;
    }

    public boolean stopping() {
        return stopping;
    }

    public Path dataset() {
        return dataset;
    }

    public Path analysis() {
        return analysis;
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public long elapsedMillis() {
        return elapsedMillis;
    }

    public long acceptedRecords() {
        return acceptedRecords;
    }

    public long acceptedBytes() {
        return acceptedBytes;
    }

    public long droppedRecords() {
        return droppedRecords;
    }

    public long limitBytes() {
        return limitBytes;
    }

    public long limitMillis() {
        return limitMillis;
    }
}
