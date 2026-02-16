package com.ulio.aegis.comms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ulio.aegis.anomaly.AnomalyResult;
import com.ulio.aegis.classifier.ClassifierResult;
import com.ulio.aegis.core.Config;
import com.ulio.aegis.telemetry.TelemetrySample;

import java.net.InetAddress;

public class Comms implements AutoCloseable {
    private final ObjectMapper objectMapper;
    private final String agentId;
    private final String host;
    private final TelemetryPoster telemetryPoster;

    public Comms(Config config) {
        this.objectMapper = new ObjectMapper();
        this.agentId = resolveAgentId(config);
        this.host = resolveHostName();
        this.telemetryPoster = buildTelemetryPoster(config);
    }

    public void emitTelemetry(
            TelemetrySample sample,
            boolean baselineReady,
            AnomalyResult anomalyResult,
            ClassifierResult classifierResult
    ) {
        if (sample == null) {
            return;
        }

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("type", "telemetry");
            root.put("timestamp", sample.getTimestamp());
            root.put("agentId", agentId);
            root.put("host", host);
            root.put("baselineReady", baselineReady);

            ObjectNode fs = root.putObject("fs");
            fs.put("modifiedPerSec", sample.getFilesModifiedPerSec());
            fs.put("createdPerSec", sample.getFilesCreatedPerSec());
            fs.put("deletedPerSec", sample.getFilesDeletedPerSec());
            fs.put("approxRenamesPerSec", sample.getApproxRenamesPerSec());

            ObjectNode disk = root.putObject("disk");
            disk.put("writeBytesPerSec", sample.getDiskWriteBytesPerSec());

            ObjectNode topWriter = root.putObject("topWriter");
            topWriter.put("pid", sample.getTopPid());
            topWriter.put("comm", sample.getTopComm());
            topWriter.put("writeBytesPerSec", sample.getTopWriteBytesPerSec());

            if (anomalyResult != null) {
                ObjectNode anomaly = root.putObject("anomaly");
                anomaly.put("score", anomalyResult.getAnomalyScore());
                anomaly.put("maxAbsZ", anomalyResult.getMaxAbsZ());

                ArrayNode flags = anomaly.putArray("flags");
                for (String flag : anomalyResult.getFlags()) {
                    flags.add(flag);
                }
            }

            if (classifierResult != null) {
                ObjectNode classifier = root.putObject("classifier");
                classifier.put("ransomwareScore", classifierResult.getRansomwareScore());
                classifier.put("verdict", classifierResult.getVerdict().name());
                classifier.put("confidence", classifierResult.getConfidence());
            }

            String payload = objectMapper.writeValueAsString(root);
            System.out.println(payload);

            if (telemetryPoster != null) {
                telemetryPoster.enqueue(payload);
            }
        } catch (Throwable e) {
            // Output failures must never stop the agent loop.
            System.err.println("Failed to emit telemetry JSON: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (telemetryPoster != null) {
            telemetryPoster.close();
        }
    }

    private TelemetryPoster buildTelemetryPoster(Config config) {
        if (config == null || !config.isPostTelemetry()) {
            return null;
        }

        String backendUrl = config.getBackendUrl();
        if (backendUrl == null || backendUrl.isBlank()) {
            return null;
        }

        try {
            return new TelemetryPoster(
                    backendUrl,
                    config.getAegisToken(),
                    config.getPostTimeoutMs(),
                    config.getPostQueueMax()
            );
        } catch (Throwable e) {
            System.err.println("Telemetry POST disabled: " + e.getMessage());
            return null;
        }
    }

    private String resolveAgentId(Config config) {
        if (config == null || config.getAgentId() == null || config.getAgentId().isBlank()) {
            return "local-vm-01";
        }
        return config.getAgentId().trim();
    }

    private String resolveHostName() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            if (hostname != null && !hostname.isBlank()) {
                return hostname;
            }
        } catch (Throwable ignored) {
            // fall through to environment fallback
        }

        String envHostname = System.getenv("HOSTNAME");
        if (envHostname != null && !envHostname.isBlank()) {
            return envHostname.trim();
        }

        return "unknown-host";
    }
}
