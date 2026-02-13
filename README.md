# aegis-agent-java

Linux-only Java 17 Maven agent for ransomware-behavior detection.

It watches a filesystem root recursively, scans `/proc` for top write-heavy processes, builds a baseline, computes anomaly flags, classifies ransomware likelihood, and prints JSON lines to stdout.

## Build (Kali/Ubuntu)

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk maven
mvn -DskipTests package
```

## Run

```bash
java -jar target/aegis-agent.jar --config config.yml
```

## Config

Edit `config.yml`:

- `watchRoot`: filesystem root to watch recursively
- `baselinePath`: baseline persistence JSON file path
- `stormWindowSeconds`: sliding window for fs per-second rates
- `zScoreThreshold`: z-score threshold for storm flags
- `thresholds.*`: absolute per-second thresholds
- `scoreThresholds.*`: classifier verdict thresholds

## Telemetry Output

Each line is JSON with this shape:

```json
{
  "type": "telemetry",
  "timestamp": "...",
  "baselineReady": true,
  "fs": {
    "modifiedPerSec": 0.0,
    "createdPerSec": 0.0,
    "deletedPerSec": 0.0,
    "approxRenamesPerSec": 0.0
  },
  "disk": {
    "writeBytesPerSec": 0.0
  },
  "topWriter": {
    "pid": -1,
    "comm": "",
    "writeBytesPerSec": 0.0
  },
  "anomaly": {
    "score": 0.0,
    "maxAbsZ": 0.0,
    "flags": []
  },
  "classifier": {
    "ransomwareScore": 0.0,
    "verdict": "SAFE",
    "confidence": 0.0
  }
}
```

`anomaly` and `classifier` are emitted only in `DETECT` mode when baseline is ready.

## Testing Storm Detection

1. Configure `watchRoot` to a writable test folder:

```bash
mkdir -p /tmp/aegis-watch
```

2. Start the agent:

```bash
java -jar target/aegis-agent.jar --config config.yml
```

3. In another shell, generate create/modify/delete bursts:

```bash
for i in $(seq 1 2000); do echo "$i" > /tmp/aegis-watch/f_$i.txt; done
for i in $(seq 1 2000); do echo "x" >> /tmp/aegis-watch/f_$i.txt; done
for i in $(seq 1 2000); do rm -f /tmp/aegis-watch/f_$i.txt; done
```

4. Generate rename-heavy bursts:

```bash
for i in $(seq 1 1000); do touch /tmp/aegis-watch/r_$i.a; mv /tmp/aegis-watch/r_$i.a /tmp/aegis-watch/r_$i.b; done
```

Watch JSON output for `WRITE_STORM`, `DELETE_STORM`, `RENAME_STORM`, and elevated classifier scores.
