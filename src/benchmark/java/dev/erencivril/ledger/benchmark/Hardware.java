package dev.erencivril.ledger.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Describes the machine, so a number is never separated from the hardware that produced it. */
final class Hardware {

    private Hardware() {}

    static String describe() {
        StringBuilder text = new StringBuilder();
        text.append("- CPU: ").append(cpuModel()).append(", ").append(Runtime.getRuntime().availableProcessors()).append(" logical CPUs\n");
        text.append("- Memory: ").append(memoryTotal()).append("\n");
        text.append("- OS: ").append(System.getProperty("os.name")).append(" ").append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append(")\n");
        text.append("- JVM: ").append(System.getProperty("java.vm.name")).append(" ").append(System.getProperty("java.runtime.version")).append("\n");
        return text.toString();
    }

    private static String cpuModel() {
        for (String line : readLines("/proc/cpuinfo")) {
            if (line.startsWith("model name")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return "unknown";
    }

    private static String memoryTotal() {
        for (String line : readLines("/proc/meminfo")) {
            if (line.startsWith("MemTotal")) {
                long kilobytes = Long.parseLong(line.replaceAll("[^0-9]", ""));
                return String.format("%.1f GiB", kilobytes / 1024.0 / 1024.0);
            }
        }
        return "unknown";
    }

    private static List<String> readLines(String file) {
        try {
            return Files.readAllLines(Path.of(file));
        } catch (IOException e) {
            return List.of();
        }
    }
}
