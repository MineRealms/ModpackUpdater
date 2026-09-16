package com.society.updater;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PublishTableModel extends AbstractTableModel {
    public static final String ADD = "添加";
    public static final String DELETE = "删除";
    public static final String IGNORE = "取消";

    public static class Row {
        public String path;
        public long size;
        public String state;

        public Row(String path, long size, String state) {
            this.path = path;
            this.size = size;
            this.state = state;
        }
    }

    private final String[] columns = {"状态", "路径", "大小"};
    private final List<Row> rows = new ArrayList<>();

    public void setRows(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        fireTableDataChanged();
    }

    public List<Row> getRows() {
        return rows;
    }

    public void addRow(Row row) {
        rows.add(row);
        fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
    }

    public boolean containsPath(String path) {
        String key = normalize(path);
        for (Row row : rows) {
            if (normalize(row.path).equals(key)) {
                return true;
            }
        }
        return false;
    }

    public void setState(int index, String state) {
        rows.get(index).state = state;
        fireTableRowsUpdated(index, index);
    }

    public static String normalize(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    public static String formatSize(long size) {
        if (size < 0) {
            return "";
        }
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        }
        if (size < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", size / 1024.0 / 1024.0);
        }
        return String.format("%.2f GB", size / 1024.0 / 1024.0 / 1024.0);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Row row = rows.get(rowIndex);
        switch (columnIndex) {
            case 0:
                return row.state;
            case 1:
                return row.path;
            case 2:
                return formatSize(row.size);
            default:
                return "";
        }
    }
}
