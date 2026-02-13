package com.ulio.aegis.comms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ulio.aegis.anomaly.AnomalyResult;
import com.ulio.aegis.classifier.ClassifierResult;
import com.ulio.aegis.telemetry.TelemetrySample;

public class Comms {
    private final ObjectMapper objectMapper;

    public Comms() {
        this.objectMapper = new ObjectMapper();
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

            System.out.println(objectMapper.writeValueAsString(root));
        } catch (Throwable e) {
            // Output failures must never stop the agent loop.
            System.err.println("Failed to emit telemetry JSON: " + e.getMessage());
        }
    }
}
