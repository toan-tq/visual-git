package com.visualgit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

public class FolderDiff {

    public enum FileStatus { EQUAL, CHANGED, LEFT_ONLY, RIGHT_ONLY }

    public static class FileEntry {
        public final String relativePath;
        public final FileStatus status;
        public final long leftSize;
        public final long rightSize;
        public final long leftModified;  // epoch millis, -1 if absent
        public final long rightModified; // epoch millis, -1 if absent
        public final boolean isDirectory;

        FileEntry(String relativePath, FileStatus status, long leftSize, long rightSize,
                  long leftModified, long rightModified, boolean isDirectory) {
            this.relativePath = relativePath;
            this.status = status;
            this.leftSize = leftSize;
            this.rightSize = rightSize;
            this.leftModified = leftModified;
            this.rightModified = rightModified;
            this.isDirectory = isDirectory;
        }
    }

    public static List<FileEntry> compare(Path leftDir, Path rightDir) throws IOException {
        Map<String, Path> leftFiles = new TreeMap<>();
        Map<String, Path> rightFiles = new TreeMap<>();

        if (Files.isDirectory(leftDir)) {
            try (Stream<Path> walk = Files.walk(leftDir)) {
                walk.forEach(p -> {
                    String rel = leftDir.relativize(p).toString();
                    if (!rel.isEmpty()) leftFiles.put(rel, p);
                });
            }
        }

        if (Files.isDirectory(rightDir)) {
            try (Stream<Path> walk = Files.walk(rightDir)) {
                walk.forEach(p -> {
                    String rel = rightDir.relativize(p).toString();
                    if (!rel.isEmpty()) rightFiles.put(rel, p);
                });
            }
        }

        // Merge all relative paths
        TreeMap<String, Object> allPaths = new TreeMap<>();
        leftFiles.keySet().forEach(k -> allPaths.put(k, null));
        rightFiles.keySet().forEach(k -> allPaths.put(k, null));

        List<FileEntry> result = new ArrayList<>();
        for (String rel : allPaths.keySet()) {
            Path left = leftFiles.get(rel);
            Path right = rightFiles.get(rel);

            if (left != null && right == null) {
                boolean isDir = Files.isDirectory(left);
                long size = isDir ? 0 : Files.size(left);
                long mod = Files.getLastModifiedTime(left).toMillis();
                result.add(new FileEntry(rel, FileStatus.LEFT_ONLY, size, -1, mod, -1, isDir));
            } else if (left == null && right != null) {
                boolean isDir = Files.isDirectory(right);
                long size = isDir ? 0 : Files.size(right);
                long mod = Files.getLastModifiedTime(right).toMillis();
                result.add(new FileEntry(rel, FileStatus.RIGHT_ONLY, -1, size, -1, mod, isDir));
            } else {
                boolean leftIsDir = Files.isDirectory(left);
                boolean rightIsDir = Files.isDirectory(right);
                if (leftIsDir && rightIsDir) {
                    long lm = Files.getLastModifiedTime(left).toMillis();
                    long rm = Files.getLastModifiedTime(right).toMillis();
                    result.add(new FileEntry(rel, FileStatus.EQUAL, 0, 0, lm, rm, true));
                    continue;
                }
                if (leftIsDir != rightIsDir) {
                    result.add(new FileEntry(rel, FileStatus.CHANGED, 0, 0, -1, -1, false));
                    continue;
                }
                long leftSize = Files.size(left);
                long rightSize = Files.size(right);
                long lm = Files.getLastModifiedTime(left).toMillis();
                long rm = Files.getLastModifiedTime(right).toMillis();
                FileStatus status;
                if (leftSize != rightSize) {
                    status = FileStatus.CHANGED;
                } else {
                    long mismatch = Files.mismatch(left, right);
                    status = (mismatch == -1) ? FileStatus.EQUAL : FileStatus.CHANGED;
                }
                result.add(new FileEntry(rel, status, leftSize, rightSize, lm, rm, false));
            }
        }

        return result;
    }
}
