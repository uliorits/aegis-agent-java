package com.ulio.aegis;

import com.ulio.aegis.core.AgentLoop;
import com.ulio.aegis.core.Config;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        Path configPath = parseConfigPath(args);

        try {
            Config config = Config.load(configPath);
            AgentLoop loop = new AgentLoop(config);
            loop.run();
        } catch (Exception e) {
            System.err.println("Failed to start agent: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Path parseConfigPath(String[] args) {
        Path defaultPath = Path.of("config.yml");
        if (args == null || args.length == 0) {
            return defaultPath;
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (("--config".equals(arg) || "-c".equals(arg)) && i + 1 < args.length) {
                return Path.of(args[i + 1]);
            }
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelpAndExit();
            }
        }

        return defaultPath;
    }

    private static void printHelpAndExit() {
        System.out.println("Usage: java -jar target/aegis-agent.jar [--config <path>]");
        System.exit(0);
    }
}
