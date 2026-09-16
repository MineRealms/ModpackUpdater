package com.society.updater;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class Log {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Object LOCK = new Object();
    private static PrintWriter writer;
    private static Path logFile;

    private Log() {
    }

    public static synchronized void init(Path dir) {
        try {
            Files.createDirectories(dir);
            logFile = dir.resolve("updater-" + LocalDateTime.now().format(FILE_STAMP) + ".log");
            writer = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8), true);
            cleanup(dir);
            info("日志文件: " + logFile);
            info("ModpackUpdater " + Updater.VERSION + " | Java " + System.getProperty("java.version") + " | " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
            info("代理: " + Pixeldrain.proxyDescription());
        } catch (IOException e) {
            writer = null;
        }
    }

    private static void cleanup(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> logs = stream
                    .filter(path -> path.getFileName().toString().startsWith("updater-") && path.getFileName().toString().endsWith(".log"))
                    .sorted(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .toList();
            for (int i = 0; i < logs.size() - 20; i++) {
                Files.deleteIfExists(logs.get(i));
            }
        } catch (IOException ignored) {
        }
    }

    public static Path file() {
        return logFile;
    }

    public static void info(String message) {
        write("INFO", message, null);
    }

    public static void warn(String message) {
        write("WARN", message, null);
    }

    public static void error(String message) {
        write("ERROR", message, null);
    }

    public static void error(String message, Throwable throwable) {
        write("ERROR", message, throwable);
    }

    private static void write(String level, String message, Throwable throwable) {
        String line = "[" + LocalDateTime.now().format(STAMP) + "] [" + level + "] [" + Thread.currentThread().getName() + "] " + message;
        synchronized (LOCK) {
            if (writer != null) {
                writer.println(line);
                if (throwable != null) {
                    throwable.printStackTrace(writer);
                }
            }
            System.out.println(line);
            if (throwable != null) {
                throwable.printStackTrace(System.out);
            }
        }
    }
}
