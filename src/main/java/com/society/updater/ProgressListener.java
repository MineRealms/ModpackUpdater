package com.society.updater;

public interface ProgressListener {
    void onLog(String message);

    void onFile(String path, int index, int total);

    void onProgress(long done, long total);

    void onFinished(boolean success, String message);
}
