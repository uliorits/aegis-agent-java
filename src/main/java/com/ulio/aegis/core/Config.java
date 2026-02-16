package com.ulio.aegis.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Config {
    public enum Mode {
        BASELINE,
        DETECT
    }

    public enum Output {
        STDOUT
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Thresholds {
        private double filesModifiedPerSec = 200.0;
        private double filesDeletedPerSec = 100.0;
        private double approxRenamesPerSec = 80.0;
        private double diskWriteBytesPerSec = 50.0 * 1024.0 * 1024.0;

        private void applyDefaults() {
            if (!Double.isFinite(filesModifiedPerSec) || filesModifiedPerSec < 0.0) {
                filesModifiedPerSec = 200.0;
            }
            if (!Double.isFinite(filesDeletedPerSec) || filesDeletedPerSec < 0.0) {
                filesDeletedPerSec = 100.0;
            }
            if (!Double.isFinite(approxRenamesPerSec) || approxRenamesPerSec < 0.0) {
                approxRenamesPerSec = 80.0;
            }
            if (!Double.isFinite(diskWriteBytesPerSec) || diskWriteBytesPerSec < 0.0) {
                diskWriteBytesPerSec = 50.0 * 1024.0 * 1024.0;
            }
        }

        public double getFilesModifiedPerSec() {
            return filesModifiedPerSec;
        }

        public void setFilesModifiedPerSec(double filesModifiedPerSec) {
            this.filesModifiedPerSec = filesModifiedPerSec;
        }

        public double getFilesDeletedPerSec() {
            return filesDeletedPerSec;
        }

        public void setFilesDeletedPerSec(double filesDeletedPerSec) {
            this.filesDeletedPerSec = filesDeletedPerSec;
        }

        public double getApproxRenamesPerSec() {
            return approxRenamesPerSec;
        }

        public void setApproxRenamesPerSec(double approxRenamesPerSec) {
            this.approxRenamesPerSec = approxRenamesPerSec;
        }

        public double getDiskWriteBytesPerSec() {
            return diskWriteBytesPerSec;
        }

        public void setDiskWriteBytesPerSec(double diskWriteBytesPerSec) {
            this.diskWriteBytesPerSec = diskWriteBytesPerSec;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScoreThresholds {
        private double suspicious = 0.55;
        private double ransomware = 0.85;

        private void applyDefaults() {
            if (!Double.isFinite(suspicious) || suspicious <= 0.0 || suspicious >= 1.0) {
                suspicious = 0.55;
            }
            if (!Double.isFinite(ransomware) || ransomware <= 0.0 || ransomware > 1.0) {
                ransomware = 0.85;
            }
            if (ransomware <= suspicious) {
                ransomware = Math.min(0.99, suspicious + 0.30);
            }
        }

        public double getSuspicious() {
            return suspicious;
        }

        public void setSuspicious(double suspicious) {
            this.suspicious = suspicious;
        }

        public double getRansomware() {
            return ransomware;
        }

        public void setRansomware(double ransomware) {
            this.ransomware = ransomware;
        }
    }

    private long samplingIntervalMs = 1000;
    private Mode mode = Mode.DETECT;
    private int baselineMinSamples = 300;
    private Output output = Output.STDOUT;

    private String watchRoot = "/tmp/aegis-watch";
    private String baselinePath = "baseline.json";
    private int stormWindowSeconds = 3;
    private double zScoreThreshold = 3.0;

    private String backendUrl = "";
    private String aegisToken = "";
    private boolean postTelemetry = true;
    private int postTimeoutMs = 1500;
    private int postQueueMax = 500;
    private String agentId = "local-vm-01";

    private Thresholds thresholds = new Thresholds();
    private ScoreThresholds scoreThresholds = new ScoreThresholds();

    public static Config load(Path path) throws IOException {
        Config defaults = new Config();
        if (path == null || !Files.exists(path)) {
            System.err.println("Config file not found, using defaults: " + path);
            defaults.applyDefaults();
            return defaults;
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        Config loaded = mapper.readValue(path.toFile(), Config.class);
        if (loaded == null) {
            defaults.applyDefaults();
            return defaults;
        }

        loaded.applyDefaults();
        return loaded;
    }

    private void applyDefaults() {
        if (samplingIntervalMs <= 0) {
            samplingIntervalMs = 1000;
        }
        if (mode == null) {
            mode = Mode.DETECT;
        }
        if (baselineMinSamples <= 0) {
            baselineMinSamples = 300;
        }
        if (output == null) {
            output = Output.STDOUT;
        }

        if (watchRoot == null || watchRoot.isBlank()) {
            watchRoot = "/tmp/aegis-watch";
        }
        if (baselinePath == null || baselinePath.isBlank()) {
            baselinePath = "baseline.json";
        }
        if (stormWindowSeconds <= 0) {
            stormWindowSeconds = 3;
        }
        if (!Double.isFinite(zScoreThreshold) || zScoreThreshold <= 0.0) {
            zScoreThreshold = 3.0;
        }

        backendUrl = backendUrl == null ? "" : backendUrl.trim();
        aegisToken = resolveAegisToken(aegisToken);

        if (postTimeoutMs <= 0) {
            postTimeoutMs = 1500;
        }
        if (postQueueMax <= 0) {
            postQueueMax = 500;
        }
        if (agentId == null || agentId.isBlank()) {
            agentId = "local-vm-01";
        }

        if (thresholds == null) {
            thresholds = new Thresholds();
        }
        thresholds.applyDefaults();

        if (scoreThresholds == null) {
            scoreThresholds = new ScoreThresholds();
        }
        scoreThresholds.applyDefaults();
    }

    private String resolveAegisToken(String configuredToken) {
        String envToken = System.getenv("AEGIS_TOKEN");
        if (envToken != null) {
            return envToken.trim();
        }

        return configuredToken == null ? "" : configuredToken.trim();
    }

    public long getSamplingIntervalMs() {
        return samplingIntervalMs;
    }

    public void setSamplingIntervalMs(long samplingIntervalMs) {
        this.samplingIntervalMs = samplingIntervalMs;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public int getBaselineMinSamples() {
        return baselineMinSamples;
    }

    public void setBaselineMinSamples(int baselineMinSamples) {
        this.baselineMinSamples = baselineMinSamples;
    }

    public Output getOutput() {
        return output;
    }

    public void setOutput(Output output) {
        this.output = output;
    }

    public String getWatchRoot() {
        return watchRoot;
    }

    public void setWatchRoot(String watchRoot) {
        this.watchRoot = watchRoot;
    }

    public String getBaselinePath() {
        return baselinePath;
    }

    public void setBaselinePath(String baselinePath) {
        this.baselinePath = baselinePath;
    }

    public int getStormWindowSeconds() {
        return stormWindowSeconds;
    }

    public void setStormWindowSeconds(int stormWindowSeconds) {
        this.stormWindowSeconds = stormWindowSeconds;
    }

    public double getZScoreThreshold() {
        return zScoreThreshold;
    }

    public void setZScoreThreshold(double zScoreThreshold) {
        this.zScoreThreshold = zScoreThreshold;
    }

    public String getBackendUrl() {
        return backendUrl;
    }

    public void setBackendUrl(String backendUrl) {
        this.backendUrl = backendUrl;
    }

    public String getAegisToken() {
        return aegisToken;
    }

    public void setAegisToken(String aegisToken) {
        this.aegisToken = aegisToken;
    }

    public boolean isPostTelemetry() {
        return postTelemetry;
    }

    public void setPostTelemetry(boolean postTelemetry) {
        this.postTelemetry = postTelemetry;
    }

    public int getPostTimeoutMs() {
        return postTimeoutMs;
    }

    public void setPostTimeoutMs(int postTimeoutMs) {
        this.postTimeoutMs = postTimeoutMs;
    }

    public int getPostQueueMax() {
        return postQueueMax;
    }

    public void setPostQueueMax(int postQueueMax) {
        this.postQueueMax = postQueueMax;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public void setThresholds(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    public ScoreThresholds getScoreThresholds() {
        return scoreThresholds;
    }

    public void setScoreThresholds(ScoreThresholds scoreThresholds) {
        this.scoreThresholds = scoreThresholds;
    }
}
