package com.society.updater;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Internal release tool: update a small explicit path set while preserving the existing manifest. */
public final class TargetedPublisher {
    private TargetedPublisher() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("source previous.json paths.json out.json tag");
        Path source = Path.of(args[0]);
        Updater updater = new Updater();
        Manifest previous = updater.fetchManifest(args[1]);
        List<String> paths = new Gson().fromJson(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8),
                new TypeToken<List<String>>() {}.getType());
        LinkedHashMap<String, ManifestFile> desired = new LinkedHashMap<>();
        for (ManifestFile file : previous.files) desired.put(file.path, file);
        Manifest generated = updater.generateManifest(source, "targeted", args[4], "both");
        for (ManifestFile file : generated.files) {
            if (!paths.contains(file.path)) throw new IllegalArgumentException("unexpected source file " + file.path);
            desired.put(file.path, file);
        }
        if (generated.files.size() != paths.size()) throw new IllegalArgumentException("missing targeted files");
        UpdaterConfig config = UpdaterConfig.load(UpdaterConfig.defaultFile());
        Manifest result = updater.uploadReleaseAndGenerateTargeted(source, config.githubToken, config.githubRepo,
                args[4], "Sunlit Valley targeted runtime update", "", "both", previous,
                new ArrayList<>(desired.values()), previous.delete, false, new ProgressListener() {
                    public void onLog(String message) { System.out.println(message); }
                    public void onFile(String path, int index, int total) { System.out.println("["+index+"/"+total+"] "+path); }
                    public void onProgress(long done, long total) {}
                    public void onFinished(boolean success, String message) { System.out.println(message); }
                }, 100L * 1024 * 1024, 900);
        result.version = args[4];
        updater.writeManifest(result, Path.of(args[3]));
        String raw = new GitHub().publish(Path.of(args[3]), config.githubToken, config.githubRepo,
                config.githubBranch, config.githubPath);
        System.out.println("published="+raw+" files="+result.files.size()+" delete="+result.delete.size());
    }
}
