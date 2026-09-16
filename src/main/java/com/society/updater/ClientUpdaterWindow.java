package com.society.updater;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;

public class ClientUpdaterWindow extends JFrame {
    private final JLabel statusLabel = new JLabel("正在检查更新，请稍候...");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final String manifestUrl;
    private final String targetDir;
    private final String side;
    private final String apiKey;

    public ClientUpdaterWindow(String manifestUrl, String targetDir, String side, String apiKey) {
        super("整合包更新");
        this.manifestUrl = manifestUrl;
        this.targetDir = targetDir;
        this.side = side;
        this.apiKey = apiKey;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(16, 20, 16, 20));
        JLabel title = new JLabel("正在检查整合包更新，请稍候...");
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        progress.setAlignmentX(Component.LEFT_ALIGNMENT);
        progress.setStringPainted(true);
        panel.add(title);
        panel.add(Box.createVerticalStrut(10));
        panel.add(statusLabel);
        panel.add(Box.createVerticalStrut(6));
        panel.add(progress);
        setContentPane(panel);
        setSize(480, 170);
        setResizable(false);
        setLocationRelativeTo(null);
        setAlwaysOnTop(true);
        start();
    }

    private void start() {
        ProgressListener listener = new ProgressListener() {
            @Override
            public void onLog(String message) {
                Log.info(message);
            }

            @Override
            public void onFile(String path, int index, int total) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("(" + index + "/" + total + ") " + path);
                    progress.setIndeterminate(false);
                    progress.setValue(0);
                });
            }

            @Override
            public void onProgress(long done, long total) {
                int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
                SwingUtilities.invokeLater(() -> {
                    if (percent < 0) {
                        progress.setIndeterminate(true);
                    } else {
                        progress.setIndeterminate(false);
                        progress.setValue(percent);
                    }
                });
            }

            @Override
            public void onFinished(boolean success, String message) {
                SwingUtilities.invokeLater(() -> finish(success, message));
            }
        };
        Thread worker = new Thread(() -> {
            try {
                Updater updater = new Updater(apiKey);
                Log.info("客户端模式: manifest=" + manifestUrl + " target=" + targetDir + " side=" + side);
                Manifest manifest = updater.fetchManifest(manifestUrl);
                updater.update(manifest, Path.of(targetDir), side, false, true, listener);
            } catch (Exception e) {
                Log.error("更新失败", e);
                listener.onFinished(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }, "client-updater");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish(boolean success, String message) {
        if (success) {
            statusLabel.setText(message);
            progress.setIndeterminate(false);
            progress.setValue(100);
            Log.info("完成: " + message);
            Timer timer = new Timer(1200, event -> {
                dispose();
                System.exit(0);
            });
            timer.setRepeats(false);
            timer.start();
        } else {
            Log.error("错误: " + message);
            statusLabel.setText("更新失败");
            JOptionPane.showMessageDialog(this,
                    "更新失败: " + message + "\n\n日志文件:\n" + Log.file(),
                    "整合包更新失败", JOptionPane.ERROR_MESSAGE);
            dispose();
            System.exit(1);
        }
    }
}
