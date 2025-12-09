/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media.util;

import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Utility class for handling file restore operation
 */
public final class FileRestoreManager {

    private static final String TAG = "FileRestoreManager";

    /**
     * Interface for providing media scanning capabilities.
     * This allows the restore logic to be decoupled from the specific MediaStore implementation.
     */
    public interface MediaScannerCallback {
        /**
         * Scans a given file to add or update it in the media store db.
         *
         * @param file The file to be scanned
         */
        void scanFile(File file);
    }

    /**
     * Restores a file or directory from the trash location to its original or specified target
     * path.
     * The method handles unprefixing the file name and recursively unprefixing children if it's a
     * directory.
     * It also cleans up empty parent directories within the trash.
     *
     * @param trashedFilePath      The absolute path of the file or directory to be restored from
     *                             trash.
     * @param targetParentPath     The desired target path for restoration. If null, the original
     *                             path
     *                             (derived from the trash structure) will be used.
     * @param mediaScannerCallback A callback to inform the MediaStore about file changes.
     * @return The absolute path of the restored item at its new location.
     * @throws IllegalArgumentException If `trashedFilePath` is null or empty.
     * @throws FileNotFoundException    If the `trashedFilePath` does not exist.
     * @throws IllegalStateException    If directory creation fails, file rename fails,
     *                                  or original name cannot be derived.
     */
    public static String restoreFile(String trashedFilePath,
            Optional<String> targetParentPath, MediaScannerCallback mediaScannerCallback)
            throws IllegalArgumentException, FileNotFoundException, IllegalStateException {

        if (trashedFilePath == null || trashedFilePath.isEmpty()) {
            throw new IllegalArgumentException(
                    "Trashed file path cannot be null or empty for restore.");
        }

        File trashedFile = new File(trashedFilePath);
        if (!trashedFile.exists()) {
            throw new FileNotFoundException("Trashed file not found: " + trashedFilePath);
        }

        String trashedFileName = trashedFile.getName();
        String prefixToUnprefix = "";
        String originalFileName;

        // Extract the prefix and original name using the defined pattern
        Matcher matcher = FileUtils.PATTERN_EXPIRES_FILE.matcher(trashedFileName);
        if (matcher.matches() && matcher.group(1).equals(FileUtils.PREFIX_TRASHED)) {
            // Group 1 is "trashed"
            // Group 2 is the timestamp
            // Group 3 is the original file name
            prefixToUnprefix = trashedFileName.substring(0,
                    matcher.start(3)); // Get the ".trashed-TIMESTAMP-" part
            originalFileName = matcher.group(3); // Get the original file name part
            Log.d(TAG, "Extracted prefix '" + prefixToUnprefix + "', original name '"
                    + originalFileName + "'");
        } else {
            throw new IllegalArgumentException(
                    "File name does not indicate a trashed item: " + trashedFileName);
        }

        String targetPath = targetParentPath.orElseGet(() -> getTargetPath(trashedFile));

        String validTargetPath = getValidTargetPath(targetPath);
        Log.d(TAG, "Restoring: " + trashedFilePath + " to " + validTargetPath);

        File targetParent = new File(validTargetPath);

        if (!isFileAllowedToRestore(trashedFile, targetParent)) {
            throw new IllegalArgumentException("Not allowed to restore file");
        }

        if (!targetParent.exists()) {
            if (!targetParent.mkdirs()) {
                if (!targetParent.exists()) {
                    Log.e(TAG, "Failed to create parent directory for restore: "
                            + targetParent.getAbsolutePath());
                    throw new IllegalStateException(
                            "Failed to create parent directory for restoration.");
                }
            }
        }


        // method to avoid file name collisions with suffix (1), (2) etc.
        File originalLocation = FileUtils.buildUniqueFile(targetParent, originalFileName);

        if (!trashedFile.renameTo(originalLocation)) {
            Log.e(TAG, "Failed to rename during restore: " + trashedFilePath + " -> "
                    + originalLocation.getAbsolutePath());
            throw new IllegalStateException("Failed to restore: Could not move file.");
        }

        // If the restored item is a directory and we successfully extracted a prefix,
        // unprefix its children recursively.
        if (originalLocation.isDirectory() && !prefixToUnprefix.isEmpty()) {
            unprefixChildrenOnDisk(originalLocation, prefixToUnprefix);
        }

        // Clean up empty parent directories in trash location
        deleteAllParentIfNonTrashed(trashedFile, mediaScannerCallback);

        // Notify MediaStore about the changes
        if (mediaScannerCallback != null) {
            mediaScannerCallback.scanFile(trashedFile); // Old trashed location removed
            mediaScannerCallback.scanFile(originalLocation); // New restored location added
        } else {
            Log.w(TAG, "MediaScannerCallback is null. MediaStore might not be updated.");
        }

        return originalLocation.getAbsolutePath();
    }

    private static boolean isFileAllowedToRestore(File trashedFile, File targetParent) {
        String trashedFileVolumePath = FileUtils.extractVolumePath(trashedFile.getAbsolutePath());
        String targetParentVolumePath = FileUtils.extractVolumePath(targetParent.getAbsolutePath());

        if (trashedFileVolumePath == null || targetParentVolumePath == null) {
            return false;
        }

        String trashedRootPath = trashedFileVolumePath + FileUtils.DIRECTORY_TRASH_STORAGE;

        // trashed file should be descendant of .trash-storage location
        if (!trashedFile.getAbsolutePath().startsWith(trashedRootPath)) {
            Log.w(TAG, "trashed file not a descendant of .trash-storage");
            return false;
        }

        // trashed file volume path should be equal to target volume path
        if (!trashedFileVolumePath.equals(targetParentVolumePath)) {
            Log.w(TAG, "trashed file volume path not equal to target volume path");
            return false;
        }

        if (FileUtils.shouldBeInvisible(targetParent.getParent())) {
            Log.w(TAG, "cannot restored to restricted path");
            return false;
        }

        return true;
    }

    /**
     * Cleans a segment by removing trash prefixes.
     *
     * @param segment The path segment to clean.
     * @return The cleaned segment, or the original if no matching prefix was found.
     */
    private static String cleanSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            return segment;
        }

        Matcher matcher = FileUtils.PATTERN_EXPIRES_FILE.matcher(segment);
        if (matcher.matches() && matcher.group(1).equals(FileUtils.PREFIX_TRASHED)) {
            return matcher.group(3); // Return the original name part
        }

        return segment;
    }

    /**
     * Cleans a full path by removing trash prefixes from all segments.
     *
     * @param targetPath The path string to clean.
     * @return The cleaned path string.
     */
    private static String getValidTargetPath(String targetPath) {
        if (targetPath == null || targetPath.isEmpty()) {
            return targetPath;
        }

        String[] segments = targetPath.split(File.separator);
        List<String> cleanedSegments = new ArrayList<>();
        for (String segment : segments) {
            cleanedSegments.add(cleanSegment(segment));
        }

        // Reconstruct path, handling leading/trailing slashes if present
        String cleanedPath = String.join(File.separator, cleanedSegments);
        if (targetPath.startsWith(File.separator) && !cleanedPath.startsWith(File.separator)) {
            cleanedPath = File.separator + cleanedPath;
        }
        if (targetPath.endsWith(File.separator) && !cleanedPath.endsWith(File.separator)) {
            cleanedPath = cleanedPath + File.separator;
        }

        return cleanedPath;
    }

    /**
     * Determines the default target path for restoration based on the trashed file's location
     * within the `.trash-storage` directory. This method take account the trash structure as
     * the original file system relative to the external storage root.
     *
     * @param file The trashed file for which to determine the default restore path.
     * @return The absolute path to the default restoration target directory.
     */
    private static String getTargetPath(File file) {
        String volumeRootPath = FileUtils.extractVolumePath(file.getAbsolutePath());
        String trashStorageRoot = volumeRootPath + FileUtils.DIRECTORY_TRASH_STORAGE;
        String pathInTrash = file.getAbsolutePath();

        String relativePathInTrash = pathInTrash.substring(trashStorageRoot.length());

        // Remove the filename itself to get the parent path
        int lastSeparator = relativePathInTrash.lastIndexOf(File.separator);
        if (lastSeparator != -1) {
            relativePathInTrash = relativePathInTrash.substring(0, lastSeparator);
        } else {
            relativePathInTrash = ""; // If it's a file directly under .trash-storage
        }

        File defaultRestoreParent = new File(volumeRootPath, relativePathInTrash);

        return defaultRestoreParent.getAbsolutePath();
    }


    /**
     * Recursively unprefixes the names of all children (files and directories)
     * within a given directory on disk, effectively restoring their original names.
     *
     * @param dir            The directory whose children need to be unprefixed.
     * @param originalPrefix The exact prefix to remove (e.g., ".trashed-TIMESTAMP-").
     */
    private static void unprefixChildrenOnDisk(File dir, String originalPrefix) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }

        for (File child : children) {
            String childName = child.getName();
            if (childName.startsWith(originalPrefix)) {
                String newName = childName.substring(originalPrefix.length());
                File renamed = new File(child.getParent(), newName);

                if (child.renameTo(renamed)) {
                    if (renamed.isDirectory()) {
                        // Recurse for subdirectories
                        unprefixChildrenOnDisk(renamed, originalPrefix);
                    }
                } else {
                    Log.w(TAG, "Failed to unprefix child: " + child.getAbsolutePath());
                }
            }
        }
    }

    /**
     * Recursively deletes empty parent directories in the trash location after a file is restored.
     * It stops when it encounters the `.trash-storage` root or a directory that is not empty
     * or does not follow the trashed naming pattern.
     *
     * @param trashedFile          The file that was just restored. Its parent directories will be
     *                             checked.
     * @param mediaScannerCallback Callback to update MediaStore for deleted directories.
     */
    private static void deleteAllParentIfNonTrashed(File trashedFile,
            MediaScannerCallback mediaScannerCallback) {
        if (trashedFile == null) {
            return;
        }

        File parent = trashedFile.getParentFile();
        File trashBase = new File(FileUtils.extractVolumePath(trashedFile.getAbsolutePath()),
                FileUtils.DIRECTORY_TRASH_STORAGE);
        File latestDeleteParentDir = null;
        while (parent != null && !parent.equals(trashBase)) { // Stop at .trash-storage root
            // If directory is empty and its name does not matches matches the trash pattern,
            // delete it
            if (parent.isDirectory() && parent.list().length == 0) {
                // Check if the directory name does not matches the trash pattern.
                // This implies it was created to hold trashed files and is now empty.
                Matcher matcher = FileUtils.PATTERN_EXPIRES_FILE.matcher(parent.getName());
                if (!matcher.matches() || !matcher.group(1).equals(FileUtils.PREFIX_TRASHED)) {
                    latestDeleteParentDir = parent;
                    File nextParent = parent.getParentFile();
                    if (!parent.delete()) {
                        Log.w(TAG, "Failed to delete empty trash parent: "
                                        + parent.getAbsolutePath());
                    }
                    parent = nextParent; // Continue to the next parent
                } else {
                    // This parent is empty but doesn't have the trash prefix, stop here
                    break;
                }
            } else {
                // Parent is not empty or not a directory; stop traversing.
                break;
            }
        }

        if (latestDeleteParentDir != null && mediaScannerCallback != null) {
            mediaScannerCallback.scanFile(latestDeleteParentDir); // Notify MediaStore of deletion
        }
    }

}
