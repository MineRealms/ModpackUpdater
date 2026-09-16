package com.society.updater;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UpdaterFrame extends JFrame {
    private final JTextField manifestField = new JTextField();
    private final JTextField targetField = new JTextField();
    private final JComboBox<String> sideBox = new JComboBox<>(new String[]{"client", "server", "both"});
    private final JCheckBox backupCheck = new JCheckBox("覆盖前备份", true);
    private final JCheckBox rememberKeyCheck = new JCheckBox("记住 Key");
    private final JPasswordField keyField = new JPasswordField();
    private final JTextField ghRepoField = new JTextField();
    private final JTextField ghBranchField = new JTextField();
    private final JTextField ghPathField = new JTextField();
    private final JPasswordField ghTokenField = new JPasswordField();
    private final JCheckBox rememberGithubCheck = new JCheckBox("记住 Token");
    private final JComboBox<String> backendBox = new JComboBox<>(new String[]{"GitHub Releases", "pixeldrain"});
    private final JTextField ghTagField = new JTextField();
    private final JTextField localManifestField = new JTextField();
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel("就绪");
    private final JTextArea logArea = new JTextArea(8, 76);
    private final PublishTableModel tableModel = new PublishTableModel();
    private final JTable fileTable = new JTable(tableModel);
    private final JButton checkButton = new JButton("检查更新");
    private final JButton updateButton = new JButton("开始更新");
    private final JButton publishAllButton = new JButton("一键发布");
    private final JButton genButton = new JButton("生成清单(本地)");
    private final JButton chooseDirButton = new JButton("选择文件夹...");
    private final JButton refreshButton = new JButton("刷新本地文件");
    private final JButton addFilesButton = new JButton("添加文件/文件夹...");
    private final JButton markAddButton = new JButton("标记为添加");
    private final JButton markDeleteButton = new JButton("标记为删除");
    private final JButton markIgnoreButton = new JButton("取消管理");
    private final Path configFile = UpdaterConfig.defaultFile();
    private final UpdaterConfig config;

    public UpdaterFrame() {
        super("Society 整合包更新器 v" + Updater.VERSION);
        config = UpdaterConfig.load(configFile);
        manifestField.setText(config.manifestUrl);
        targetField.setText(config.targetDir);
        if (config.side != null && !config.side.isBlank()) {
            sideBox.setSelectedItem(config.side);
        }
        backupCheck.setSelected(config.backup);
        rememberKeyCheck.setSelected(config.rememberKey);
        if (config.rememberKey && config.apiKey != null && !config.apiKey.isBlank()) {
            keyField.setText(config.apiKey);
        }
        ghRepoField.setText(config.githubRepo);
        ghBranchField.setText(config.githubBranch);
        ghPathField.setText(config.githubPath);
        rememberGithubCheck.setSelected(config.rememberGithub);
        if (config.rememberGithub && config.githubToken != null && !config.githubToken.isBlank()) {
            ghTokenField.setText(config.githubToken);
        }
        if (config.backend != null && !config.backend.isBlank()) {
            backendBox.setSelectedItem("pixeldrain".equalsIgnoreCase(config.backend) ? "pixeldrain" : "GitHub Releases");
        }
        ghTagField.setText(config.releaseTag);
        localManifestField.setText(config.localManifest);
        manifestField.setToolTipText("客户端读取的清单地址，例如 https://raw.githubusercontent.com/MineRealms/UpdateRepo/main/manifest.json");
        ghRepoField.setToolTipText("owner/repo，例如 MineRealms/UpdateRepo");
        ghBranchField.setToolTipText("分支，默认 main");
        ghPathField.setToolTipText("仓库内路径，默认 manifest.json");
        ghTagField.setToolTipText("Release 标签，建议固定不变(如 latest)，附件会原地替换");
        localManifestField.setToolTipText("本机保存的清单文件，用于对比上次发布状态");
        publishAllButton.setToolTipText("按列表发布: 添加=上传/覆盖, 删除=让客户端删除, 取消=不管");
        fileTable.setToolTipText("路径保留文件夹结构，aaa\\bb.jar 与 ccc\\bb.jar 互不影响");
        buildUi();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
        setMinimumSize(new Dimension(960, 700));
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(10, 10, 6, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        form.add(new JLabel("清单地址:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.gridwidth = 2;
        form.add(manifestField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        c.gridwidth = 1;
        form.add(new JLabel("游戏目录:"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(targetField, c);
        c.gridx = 2;
        c.weightx = 0;
        JButton browseButton = new JButton("浏览...");
        browseButton.addActionListener(e -> chooseTargetDir());
        form.add(browseButton, c);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        options.add(new JLabel("端类型:"));
        options.add(sideBox);
        options.add(backupCheck);
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 3;
        c.weightx = 1;
        form.add(options, c);

        JPanel keyRow = new JPanel(new BorderLayout(6, 0));
        keyRow.add(new JLabel("API Key:"), BorderLayout.WEST);
        keyRow.add(keyField, BorderLayout.CENTER);
        JPanel keyEast = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        keyEast.add(new JLabel("pixeldrain 上传/认证下载需要"));
        keyEast.add(rememberKeyCheck);
        keyRow.add(keyEast, BorderLayout.EAST);
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 3;
        c.weightx = 1;
        form.add(keyRow, c);

        JPanel ghRow = new JPanel(new BorderLayout(6, 0));
        ghRow.add(new JLabel("GitHub:"), BorderLayout.WEST);
        JPanel ghFields = new JPanel(new GridLayout(1, 3, 6, 0));
        ghFields.add(ghRepoField);
        ghFields.add(ghBranchField);
        ghFields.add(ghPathField);
        ghRow.add(ghFields, BorderLayout.CENTER);
        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 3;
        c.weightx = 1;
        form.add(ghRow, c);

        JPanel ghTokenRow = new JPanel(new BorderLayout(6, 0));
        ghTokenRow.add(new JLabel("GH Token:"), BorderLayout.WEST);
        ghTokenRow.add(ghTokenField, BorderLayout.CENTER);
        JPanel ghTokenEast = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ghTokenEast.add(rememberGithubCheck);
        ghTokenRow.add(ghTokenEast, BorderLayout.EAST);
        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 3;
        c.weightx = 1;
        form.add(ghTokenRow, c);

        JPanel publishRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        publishRow.add(new JLabel("发布方式:"));
        publishRow.add(backendBox);
        publishRow.add(new JLabel("Release 标签:"));
        ghTagField.setColumns(10);
        publishRow.add(ghTagField);
        publishRow.add(new JLabel("本地清单:"));
        localManifestField.setColumns(22);
        publishRow.add(localManifestField);
        JButton browseManifestButton = new JButton("浏览...");
        browseManifestButton.addActionListener(e -> chooseLocalManifest());
        publishRow.add(browseManifestButton);
        c.gridx = 0;
        c.gridy = 6;
        c.gridwidth = 3;
        c.weightx = 1;
        form.add(publishRow, c);

        add(form, BorderLayout.NORTH);

        JPanel filePanel = new JPanel(new BorderLayout());
        JPanel fileToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chooseDirButton.addActionListener(e -> {
            chooseTargetDir();
            refreshFiles();
        });
        refreshButton.addActionListener(e -> refreshFiles());
        addFilesButton.addActionListener(e -> addFilesDialog());
        markAddButton.addActionListener(e -> applyState(PublishTableModel.ADD));
        markDeleteButton.addActionListener(e -> applyState(PublishTableModel.DELETE));
        markIgnoreButton.addActionListener(e -> applyState(PublishTableModel.IGNORE));
        fileToolbar.add(chooseDirButton);
        fileToolbar.add(refreshButton);
        fileToolbar.add(addFilesButton);
        fileToolbar.add(new JLabel("|"));
        fileToolbar.add(markAddButton);
        fileToolbar.add(markDeleteButton);
        fileToolbar.add(markIgnoreButton);
        filePanel.add(fileToolbar, BorderLayout.NORTH);

        fileTable.setRowHeight(22);
        fileTable.setAutoCreateRowSorter(true);
        fileTable.getColumnModel().getColumn(0).setMaxWidth(80);
        fileTable.getColumnModel().getColumn(2).setMaxWidth(110);
        fileTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                String state = String.valueOf(value);
                if (isSelected) {
                    component.setForeground(Color.WHITE);
                } else if (PublishTableModel.ADD.equals(state)) {
                    component.setForeground(new Color(0, 130, 0));
                } else if (PublishTableModel.DELETE.equals(state)) {
                    component.setForeground(Color.RED);
                } else {
                    component.setForeground(Color.GRAY);
                }
                return component;
            }
        });
        filePanel.add(new JScrollPane(fileTable), BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(new EmptyBorder(0, 0, 0, 0));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, filePanel, logScroll);
        splitPane.setResizeWeight(0.62);
        splitPane.setBorder(new EmptyBorder(0, 10, 6, 10));
        add(splitPane, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        checkButton.addActionListener(e -> runTask(true));
        updateButton.addActionListener(e -> runTask(false));
        publishAllButton.addActionListener(e -> publishAll());
        genButton.addActionListener(e -> generateManifest());
        buttons.add(checkButton);
        buttons.add(updateButton);
        buttons.add(publishAllButton);
        buttons.add(genButton);

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.setBorder(new EmptyBorder(6, 10, 10, 10));
        JPanel buttonRow = new JPanel(new BorderLayout());
        buttonRow.add(buttons, BorderLayout.WEST);
        south.add(buttonRow);
        progress.setStringPainted(true);
        south.add(progress);
        south.add(statusLabel);
        add(south, BorderLayout.SOUTH);
    }

    private void chooseTargetDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择整合包目录");
        String current = targetField.getText().trim();
        if (!current.isEmpty() && Files.isDirectory(Path.of(current))) {
            chooser.setCurrentDirectory(Path.of(current).toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            targetField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseLocalManifest() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择本地清单文件");
        chooser.setSelectedFile(new File("manifest.json"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            localManifestField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void refreshFiles() {
        String source = targetField.getText().trim();
        if (source.isEmpty() || !Files.isDirectory(Path.of(source))) {
            warn("请先选择有效的游戏目录");
            return;
        }
        saveConfig();
        setBusy(true);
        appendLog("刷新本地文件: " + source);
        ProgressListener listener = listener();
        Thread worker = new Thread(() -> {
            try {
                Path root = Path.of(source).toAbsolutePath().normalize();
                Map<String, String> found = new LinkedHashMap<>();
                try (var stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile)
                            .filter(path -> !Updater.isSkippedPath(root.relativize(path).toString()))
                            .sorted()
                            .forEach(path -> found.put(root.relativize(path).toString().replace('\\', '/'), ""));
                }
                Map<String, PublishTableModel.Row> existing = new LinkedHashMap<>();
                for (PublishTableModel.Row row : tableModel.getRows()) {
                    existing.put(PublishTableModel.normalize(row.path), row);
                }
                Map<String, String> previousPaths = new LinkedHashMap<>();
                String localManifest = localManifestField.getText().trim();
                if (!localManifest.isEmpty() && Files.isRegularFile(Path.of(localManifest))) {
                    try {
                        Manifest previous = new Updater().fetchManifest(localManifest);
                        if (previous.files != null) {
                            for (ManifestFile file : previous.files) {
                                if (file != null && file.path != null && !file.path.isBlank()) {
                                    previousPaths.put(PublishTableModel.normalize(file.path), file.path);
                                }
                            }
                        }
                    } catch (Exception e) {
                        listener.onLog("上次清单读取失败: " + e.getMessage());
                    }
                }
                List<PublishTableModel.Row> rows = new ArrayList<>();
                for (Map.Entry<String, String> entry : found.entrySet()) {
                    String path = entry.getKey();
                    PublishTableModel.Row old = existing.get(PublishTableModel.normalize(path));
                    long size = sizeOf(root.resolve(path.replace('/', File.separatorChar)));
                    rows.add(new PublishTableModel.Row(path, size, old != null ? old.state : PublishTableModel.ADD));
                }
                Set<String> foundKeys = new LinkedHashSet<>();
                for (String path : found.keySet()) {
                    foundKeys.add(PublishTableModel.normalize(path));
                }
                for (Map.Entry<String, String> entry : previousPaths.entrySet()) {
                    if (!foundKeys.contains(entry.getKey())) {
                        PublishTableModel.Row old = existing.get(entry.getKey());
                        rows.add(new PublishTableModel.Row(entry.getValue(), old != null ? old.size : -1, PublishTableModel.DELETE));
                    }
                }
                rows.sort(Comparator.comparing(row -> row.path));
                SwingUtilities.invokeLater(() -> {
                    tableModel.setRows(rows);
                    statusLabel.setText("共 " + rows.size() + " 个文件 (添加=" + countState(rows, PublishTableModel.ADD)
                            + " 删除=" + countState(rows, PublishTableModel.DELETE)
                            + " 取消=" + countState(rows, PublishTableModel.IGNORE) + ")");
                    setBusy(false);
                });
                Log.info("刷新完成: " + rows.size() + " 个文件");
            } catch (Exception e) {
                Log.error("刷新失败", e);
                listener.onFinished(false, e.getMessage());
            }
        }, "refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void addFilesDialog() {
        String source = targetField.getText().trim();
        if (source.isEmpty() || !Files.isDirectory(Path.of(source))) {
            warn("请先选择有效的游戏目录");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("添加文件/文件夹");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path root = Path.of(source).toAbsolutePath().normalize();
        List<PublishTableModel.Row> added = new ArrayList<>();
        for (File file : chooser.getSelectedFiles()) {
            Path path = file.toPath().toAbsolutePath().normalize();
            if (!path.startsWith(root)) {
                appendLog("跳过(不在游戏目录内): " + path);
                continue;
            }
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    stream.filter(Files::isRegularFile)
                            .sorted()
                            .forEach(item -> {
                                String relative = root.relativize(item).toString().replace('\\', '/');
                                if (Updater.isSkippedPath(relative)) {
                                    return;
                                }
                                added.add(new PublishTableModel.Row(relative, sizeOf(item), PublishTableModel.ADD));
                            });
                } catch (IOException e) {
                    warn("读取目录失败: " + e.getMessage());
                }
            } else {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Updater.isSkippedPath(relative)) {
                    appendLog("跳过(默认排除): " + relative);
                    continue;
                }
                added.add(new PublishTableModel.Row(relative, sizeOf(path), PublishTableModel.ADD));
            }
        }
        int count = 0;
        for (PublishTableModel.Row row : added) {
            if (!tableModel.containsPath(row.path)) {
                tableModel.addRow(row);
                count++;
            }
        }
        appendLog("已添加 " + count + " 项到列表");
    }

    private void applyState(String state) {
        int[] selected = fileTable.getSelectedRows();
        if (selected.length == 0) {
            warn("请先在列表中选择文件");
            return;
        }
        for (int index : selected) {
            tableModel.setState(fileTable.convertRowIndexToModel(index), state);
        }
        appendLog("已标记 " + selected.length + " 项为: " + state);
    }

    private void runTask(boolean checkOnly) {
        String manifestUrl = manifestField.getText().trim();
        String target = targetField.getText().trim();
        if (manifestUrl.isEmpty() || target.isEmpty()) {
            warn("请填写清单地址和游戏目录");
            return;
        }
        saveConfig();
        setBusy(true);
        appendLog(checkOnly ? "开始检查更新..." : "开始更新...");
        ProgressListener listener = listener();
        Thread worker = new Thread(() -> {
            try {
                Updater updater = new Updater(new String(keyField.getPassword()));
                Manifest manifest = updater.fetchManifest(manifestUrl);
                updater.update(manifest, Path.of(target), String.valueOf(sideBox.getSelectedItem()), checkOnly, backupCheck.isSelected(), listener);
            } catch (Exception e) {
                listener.onFinished(false, e.getMessage());
            }
        }, "updater");
        worker.setDaemon(true);
        worker.start();
    }

    private void publishAll() {
        String source = targetField.getText().trim();
        if (source.isEmpty() || !Files.isDirectory(Path.of(source))) {
            warn("请先选择有效的游戏目录");
            return;
        }
        if (tableModel.getRows().isEmpty()) {
            warn("列表为空，请先点\"刷新本地文件\"");
            return;
        }
        String localManifest = localManifestField.getText().trim();
        if (localManifest.isEmpty()) {
            Path out = chooseSaveFile("manifest.json");
            if (out == null) {
                return;
            }
            localManifest = out.toString();
            localManifestField.setText(localManifest);
        }
        boolean githubBackend = "GitHub Releases".equals(backendBox.getSelectedItem());
        String ghToken = new String(ghTokenField.getPassword()).trim();
        String ghRepo = ghRepoField.getText().trim();
        String apiKey = new String(keyField.getPassword()).trim();
        if (ghToken.isEmpty() || ghRepo.isEmpty()) {
            warn("发布清单需要填写 GitHub 仓库和 Token");
            return;
        }
        if (githubBackend) {
            if (ghTagField.getText().trim().isEmpty()) {
                warn("GitHub Releases 模式需要填写 Release 标签，例如 latest");
                return;
            }
        } else if (apiKey.isEmpty()) {
            warn("pixeldrain 模式需要填写 API Key");
            return;
        }
        saveConfig();
        setBusy(true);
        Path root = Path.of(source).toAbsolutePath().normalize();
        Path manifestPath = Path.of(localManifest);
        String version = Updater.autoVersion();
        String tag = ghTagField.getText().trim();
        String branch = ghBranchField.getText().trim();
        String ghPath = ghPathField.getText().trim();
        String side = String.valueOf(sideBox.getSelectedItem());
        List<PublishTableModel.Row> snapshot = new ArrayList<>(tableModel.getRows());
        ProgressListener listener = listener();
        Thread worker = new Thread(() -> {
            try {
                Updater updater = new Updater(apiKey);
                Manifest previous = null;
                if (Files.isRegularFile(manifestPath)) {
                    try {
                        previous = updater.fetchManifest(manifestPath.toString());
                        listener.onLog("已加载上次清单: " + manifestPath);
                    } catch (Exception e) {
                        listener.onLog("上次清单读取失败，将全量上传: " + e.getMessage());
                    }
                }
                List<ManifestFile> desiredFiles = new ArrayList<>();
                List<String> desiredDeletes = new ArrayList<>();
                int index = 0;
                int ignored = 0;
                for (PublishTableModel.Row row : snapshot) {
                    if (PublishTableModel.IGNORE.equals(row.state)) {
                        ignored++;
                        continue;
                    }
                    if (PublishTableModel.DELETE.equals(row.state)) {
                        desiredDeletes.add(row.path);
                        continue;
                    }
                    Path local = root.resolve(row.path.replace('/', File.separatorChar));
                    if (!Files.isRegularFile(local)) {
                        listener.onLog("本地文件不存在，跳过: " + row.path);
                        continue;
                    }
                    index++;
                    listener.onLog("计算哈希 (" + index + "): " + row.path);
                    ManifestFile file = new ManifestFile();
                    file.path = row.path;
                    file.size = Files.size(local);
                    file.sha256 = HashUtil.sha256(local);
                    file.side = side;
                    desiredFiles.add(file);
                }
                listener.onLog("发布计划: 添加/更新 " + desiredFiles.size() + " 个，删除 " + desiredDeletes.size() + " 个，取消 " + ignored + " 个");
                Manifest manifest;
                if (githubBackend) {
                    manifest = updater.uploadReleaseAndGenerate(root, ghToken, ghRepo, tag, null, "", side, previous, desiredFiles, desiredDeletes, false, listener);
                } else {
                    manifest = updater.uploadAndGenerate(root, apiKey, null, version, side, previous, desiredFiles, desiredDeletes, false, listener);
                }
                manifest.version = version;
                updater.writeManifest(manifest, manifestPath);
                listener.onLog("清单已生成: " + manifestPath);
                String raw = new GitHub().publish(manifestPath, ghToken, ghRepo, branch, ghPath);
                listener.onFinished(true, "一键发布完成 (版本 " + version + ")，客户端清单: " + raw);
            } catch (Exception e) {
                listener.onFinished(false, e.getMessage());
            }
        }, "publish-all");
        worker.setDaemon(true);
        worker.start();
    }

    private void generateManifest() {
        String source = targetField.getText().trim();
        if (source.isEmpty() || !Files.isDirectory(Path.of(source))) {
            warn("请先选择有效的游戏目录");
            return;
        }
        Path out = chooseSaveFile("manifest.json");
        if (out == null) {
            return;
        }
        setBusy(true);
        ProgressListener listener = listener();
        Thread worker = new Thread(() -> {
            try {
                Updater updater = new Updater();
                Manifest manifest = updater.generateManifest(Path.of(source), null, Updater.autoVersion(), String.valueOf(sideBox.getSelectedItem()));
                updater.writeManifest(manifest, out);
                listener.onFinished(true, "已生成清单: " + out + " (" + manifest.files.size() + " 个文件)");
            } catch (Exception e) {
                listener.onFinished(false, e.getMessage());
            }
        }, "manifest");
        worker.setDaemon(true);
        worker.start();
    }

    private Path chooseSaveFile(String defaultName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存清单文件");
        chooser.setSelectedFile(new File(defaultName));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        return chooser.getSelectedFile().toPath();
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.WARNING_MESSAGE);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    private static long countState(List<PublishTableModel.Row> rows, String state) {
        long count = 0;
        for (PublishTableModel.Row row : rows) {
            if (state.equals(row.state)) {
                count++;
            }
        }
        return count;
    }

    private void saveConfig() {
        config.manifestUrl = manifestField.getText().trim();
        config.targetDir = targetField.getText().trim();
        config.side = String.valueOf(sideBox.getSelectedItem());
        config.rememberKey = rememberKeyCheck.isSelected();
        config.apiKey = config.rememberKey ? new String(keyField.getPassword()).trim() : "";
        config.githubRepo = ghRepoField.getText().trim();
        config.githubBranch = ghBranchField.getText().trim();
        config.githubPath = ghPathField.getText().trim();
        config.rememberGithub = rememberGithubCheck.isSelected();
        config.githubToken = config.rememberGithub ? new String(ghTokenField.getPassword()).trim() : "";
        config.backend = "pixeldrain".equals(backendBox.getSelectedItem()) ? "pixeldrain" : "github";
        config.releaseTag = ghTagField.getText().trim();
        config.localManifest = localManifestField.getText().trim();
        config.backup = backupCheck.isSelected();
        config.save(configFile);
    }

    private ProgressListener listener() {
        return new ProgressListener() {
            @Override
            public void onLog(String message) {
                Log.info(message);
                SwingUtilities.invokeLater(() -> appendLog(message));
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
                if (success) {
                    Log.info(message);
                } else {
                    Log.error(message);
                }
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    progress.setIndeterminate(false);
                    statusLabel.setText(message);
                    appendLog(message);
                    if (!success) {
                        JOptionPane.showMessageDialog(UpdaterFrame.this, message, "错误", JOptionPane.ERROR_MESSAGE);
                    }
                });
            }
        };
    }

    private void setBusy(boolean busy) {
        checkButton.setEnabled(!busy);
        updateButton.setEnabled(!busy);
        publishAllButton.setEnabled(!busy);
        genButton.setEnabled(!busy);
        chooseDirButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        addFilesButton.setEnabled(!busy);
        markAddButton.setEnabled(!busy);
        markDeleteButton.setEnabled(!busy);
        markIgnoreButton.setEnabled(!busy);
    }

    private void appendLog(String message) {
        logArea.append(message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
