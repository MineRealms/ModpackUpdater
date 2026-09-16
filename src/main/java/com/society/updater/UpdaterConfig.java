package com.society.updater;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class UpdaterConfig {
    public String manifestUrl = "";
    public String targetDir = "";
    public String side = "client";
    public String apiKey = "";
    public boolean rememberKey = false;
    public String githubRepo = "";
    public String githubBranch = "main";
    public String githubPath = "manifest.json";
    public String githubToken = "";
    public boolean rememberGithub = false;
    public String backend = "github";
    public String releaseTag = "latest";
    public String localManifest = "";
    public boolean backup = true;

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".modpackupdater", "updater.properties");
    }

    public static UpdaterConfig load(Path file) {
        UpdaterConfig config = new UpdaterConfig();
        if (file != null && Files.isRegularFile(file)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException ignored) {
            }
            config.manifestUrl = properties.getProperty("manifest", config.manifestUrl);
            config.targetDir = properties.getProperty("target", config.targetDir);
            config.side = properties.getProperty("side", config.side);
            config.apiKey = properties.getProperty("apiKey", config.apiKey);
            config.rememberKey = Boolean.parseBoolean(properties.getProperty("rememberKey", "false"));
            config.githubRepo = properties.getProperty("githubRepo", config.githubRepo);
            config.githubBranch = properties.getProperty("githubBranch", config.githubBranch);
            config.githubPath = properties.getProperty("githubPath", config.githubPath);
            config.githubToken = properties.getProperty("githubToken", config.githubToken);
            config.rememberGithub = Boolean.parseBoolean(properties.getProperty("rememberGithub", "false"));
            config.backend = properties.getProperty("backend", config.backend);
            config.releaseTag = properties.getProperty("releaseTag", config.releaseTag);
            config.localManifest = properties.getProperty("localManifest", config.localManifest);
            config.backup = Boolean.parseBoolean(properties.getProperty("backup", "true"));
        }
        return config;
    }

    public void save(Path file) {
        Properties properties = new Properties();
        properties.setProperty("manifest", manifestUrl == null ? "" : manifestUrl);
        properties.setProperty("target", targetDir == null ? "" : targetDir);
        properties.setProperty("side", side == null ? "client" : side);
        properties.setProperty("apiKey", apiKey == null ? "" : apiKey);
        properties.setProperty("rememberKey", String.valueOf(rememberKey));
        properties.setProperty("githubRepo", githubRepo == null ? "" : githubRepo);
        properties.setProperty("githubBranch", githubBranch == null ? "main" : githubBranch);
        properties.setProperty("githubPath", githubPath == null ? "manifest.json" : githubPath);
        properties.setProperty("githubToken", githubToken == null ? "" : githubToken);
        properties.setProperty("rememberGithub", String.valueOf(rememberGithub));
        properties.setProperty("backend", backend == null ? "github" : backend);
        properties.setProperty("releaseTag", releaseTag == null ? "latest" : releaseTag);
        properties.setProperty("localManifest", localManifest == null ? "" : localManifest);
        properties.setProperty("backup", String.valueOf(backup));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "ModpackUpdater");
            }
        } catch (IOException ignored) {
        }
    }
}
