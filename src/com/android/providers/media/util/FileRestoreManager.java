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

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * Utility class for handling file restore operation
 */
public final class FileRestoreManager {

    private static final String TAG = "FileRestoreManager";

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
     * @param fileRenameCallback   A callback to rename the file.
     * @param mediaScannerCallback A callback to inform the MediaStore about file changes.
     * @return The absolute path of the restored item at its new location.
     * @throws IllegalArgumentException If `trashedFilePath` is null or empty.
     * @throws FileNotFoundException    If the `trashedFilePath` does not exist.
     * @throws IllegalStateException    If directory creation fails, file rename fails,
     *                                  or original name cannot be derived.
     */
    public static String restoreFile(String trashedFilePath,
            Optional<String> targetParentPath,
            FileTrashManager.FileRenameCallback fileRenameCallback,
            FileTrashManager.MediaScannerCallback mediaScannerCallback)
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
        String originalFileName;

        // Extract the prefix and original name using the defined pattern
        Matcher matcher = FileUtils.PATTERN_EXPIRES_FILE.matcher(trashedFileName);
        if (matcher.matches() && matcher.group(1).equals(FileUtils.PREFIX_TRASHED)) {
            originalFileName = matcher.group(3);
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
        boolean isRenameSuccess = fileRenameCallback.renameFile(trashedFilePath,
                originalLocation.getAbsolutePath()) == 0;

        if (!isRenameSuccess) {
            Log.e(TAG, "Failed to rename during restore: " + trashedFilePath + " -> "
                    + originalLocation.getAbsolutePath());
            throw new IllegalStateException("Failed to restore: Could not move file.");
        }

        // Clean up empty parent directories in trash location
        deleteAllParentIfNonTrashed(trashedFile, mediaScannerCallback);

        return originalLocation.getAbsolutePath();
    }

    /**
     * Reconstructs the restored path by cleaning trash prefixes from each component of the relative
     * path.
     *
     * @param parentPath   The absolute parent path.
     * @param relativePath The relative path containing trashed components.
     * @return The combined path with trashed components restored to their original names.
     */
    public static String getRestoredPath(String parentPath, String relativePath) {
        String newRelativePath = Arrays.stream(relativePath.split("/"))
                .map(component -> {
                    Matcher componentMatcher = FileUtils.PATTERN_EXPIRES_FILE.matcher(component);
                    if (componentMatcher.matches() && componentMatcher.group(1).equals(
                            FileUtils.PREFIX_TRASHED)) {
                        return componentMatcher.group(3);
                    }
                    return component;
                })
                .collect(Collectors.joining("/"));
        return parentPath + "/" + newRelativePath;
    }

    /**
     * Determines the default target path for restoration based on the trashed file's location
     * within the `.trash-storage` directory. This method take account the trash structure as
     * the original file system relative to the external storage root.
     *
     * <p>
     * For example, if a file originally at {@code /storage/emulated/0/DCIM/image.jpg} is moved
     * to {@code /storage/emulated/0/.trash-storage/DCIM/.trashed-123-image.jpg}, this method
     * will return {@code /storage/emulated/0/DCIM}.
     *
     * @param file The trashed file for which to determine the default restore path.
     * @return The absolute path to the default restoration target directory.
     */
    public static String getTargetPath(File file) {
        if (FileUtils.isTrashFileInPlace(file.getAbsolutePath())) {
            return file.getParent();
        }

        // File should exist inside the trash directory.
        if (!FileUtils.isTrashedFileInTrashDirectory(file.getAbsolutePath())) {
            return null;
        }

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
     * Recursively deletes empty parent directories in the trash location after a file is restored.
     * It stops when it encounters the `.trash-storage` root or a directory that is not empty
     * or does not follow the trashed naming pattern.
     *
     * @param trashedFile          The file that was just restored. Its parent directories will be
     *                             checked.
     * @param mediaScannerCallback Callback to update MediaStore for deleted directories.
     */
    public static void deleteAllParentIfNonTrashed(File trashedFile,
            FileTrashManager.MediaScannerCallback mediaScannerCallback) {
        if (trashedFile == null || trashedFile.getParentFile() == null) {
            return;
        }

        File nextParentToBeChecked = trashedFile.getParentFile();
        final File trashBase = new File(
                FileUtils.extractVolumePath(trashedFile.getAbsolutePath()),
                FileUtils.DIRECTORY_TRASH_STORAGE);
        File directoryToBeScanned = null;
        while (nextParentToBeChecked != null && !nextParentToBeChecked.equals(trashBase)) {
            // Stop if the parent is not an empty directory.
            String[] children = nextParentToBeChecked.list();
            if (children == null || children.length > 0) {
                break;
            }

            // Stop if the directory name matches the standard trashed item pattern. This prevents
            // deleting actual trashed folders that happen to be empty.
            if (FileUtils.isTrashedPath(nextParentToBeChecked.getAbsolutePath())) {
                break;
            }

            // This directory is an empty, non-standard parent created for path preservation.
            // It's safe to delete it.
            directoryToBeScanned = nextParentToBeChecked.getParentFile();
            if (!nextParentToBeChecked.delete()) {
                Log.w(TAG, "Failed to delete empty trash parent: "
                        + nextParentToBeChecked.getAbsolutePath());
                // If deletion fails, we can't safely proceed up the directory tree.
                break;
            }
            nextParentToBeChecked = directoryToBeScanned;
        }

        // Notify MediaStore of deletion
        if (directoryToBeScanned != null && mediaScannerCallback != null) {
            mediaScannerCallback.scanFile(directoryToBeScanned);
        }
    }

    /**
     * Cleans a full path by removing trash prefixes from all segments.
     * <p>
     * For example:
     * {@code /path/to/.trashed-1234-Folder/.trashed-1234-File.txt} ->
     * {@code /path/to/Folder/File.txt}
     *
     * @param targetPath The path string to clean.
     * @return The cleaned path string.
     */
    public static String getValidTargetPath(String targetPath) {
        if (targetPath == null || targetPath.isEmpty()) {
            return targetPath;
        }

        String[] segments = targetPath.split(File.separator);
        List<String> cleanedSegments = new ArrayList<>();
        for (String segment : segments) {
            cleanedSegments.add(cleanTrashPrefix(segment));
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
     * Renames descendants of a restored directory to remove their trash prefix.
     * When a directory is restored, its own name is unprefixed (e.g., from
     * ".trashed-123-foo" to "foo"). This method ensures that its descendants are also
     * renamed to remove the same trash prefix (e.g., from ".trashed-123-bar" to "bar")
     * recursively.
     *
     * @param trashedFolder The file object for the directory as it existed in the trash, used to
     *                      determine the prefix that needs to be removed.
     * @param restoreFolder The file object for the directory after it has been restored.
     * @return {@code 0} on success, or an errno value on failure to rename a child.
     */
    public static int restoreChildrenOnDisk(File trashedFolder, File restoreFolder) {
        String prefixToUnprefix = null;
        String trashedFileName = trashedFolder.getName();
        // Extract the prefix and original name using the defined pattern
        Matcher matcher = FileUtils.PATTERN_EXPIRES_FILE.matcher(trashedFolder.getName());
        if (matcher.matches() && matcher.group(1).equals(FileUtils.PREFIX_TRASHED)) {
            // Group 1 is "trashed"
            // Group 2 is the timestamp
            // Group 3 is the original file name
            prefixToUnprefix = trashedFileName.substring(0,
                    matcher.start(3)); // Get the ".trashed-TIMESTAMP-" part
        }

        if (TextUtils.isEmpty(prefixToUnprefix)) {
            // Returning the errno value to indicate failure
            return OsConstants.EACCES;
        }

        return restoreChildrenOnDisk(restoreFolder, prefixToUnprefix);
    }

    /**
     * Validates if a restored file's destination is its original parent directory.
     *
     * @param currentPath   The absolute path of the item in the trash.
     * @param resultantPath The path where the item was restored.
     * @return {@code true} if the restore path is the original parent directory, {@code false}
     * otherwise.
     **/
    public static boolean isValidRestoreOperation(String currentPath, String resultantPath) {
        String currentParentPath = FileRestoreManager.getTargetPath(
                new File(currentPath));
        if (currentParentPath == null || currentParentPath.isEmpty()) {
            return false;
        }
        // The currentParentPath might contain the trash prefix on its ancestor folders,
        // so it needs to be cleaned up to retrieve the actual non-trashed path.
        String cleanNonTrashedCurrentParent = FileRestoreManager.getValidTargetPath(
                currentParentPath);
        File resultantFile = new File(resultantPath);
        if (!resultantFile.getParent().equalsIgnoreCase(cleanNonTrashedCurrentParent)) {
            return false;
        }

        String currentFileName = cleanTrashPrefix(new File(currentPath).getName());
        String resultantFileName = FileUtils.normalizeFileName(resultantFile.getName());
        return currentFileName.equalsIgnoreCase(resultantFileName);
    }

    /**
     * Cleans a path segment by removing the trash prefix if present.
     * <p>
     * For example: {@code .trashed-1629292929-foo.jpg} becomes {@code foo.jpg}.
     *
     * @param segment The path segment to clean.
     * @return The cleaned segment, or the original if no matching prefix was found.
     */
    public static String cleanTrashPrefix(String segment) {
        if (segment == null || segment.isEmpty()) {
            return segment;
        }

        Matcher matcher = FileUtils.PATTERN_EXPIRES_FILE.matcher(segment);
        if (matcher.matches() && matcher.group(1).equals(FileUtils.PREFIX_TRASHED)) {
            return matcher.group(3); // Return the original name part
        }

        return segment;
    }

    private static boolean isFileAllowedToRestore(File trashedFile, File targetParent) {
        String trashedFileVolumePath = FileUtils.extractVolumePath(trashedFile.getAbsolutePath());
        String targetParentVolumePath = FileUtils.extractVolumePath(targetParent.getAbsolutePath());

        if (trashedFileVolumePath == null || targetParentVolumePath == null) {
            return false;
        }

        boolean isFileTrashedInPlace = FileUtils.isTrashFileInPlace(trashedFile.getAbsolutePath());
        boolean isFileInTrashStorageDir = FileUtils.isTrashedFileInTrashDirectory(
                trashedFile.getAbsolutePath());

        if (!isFileTrashedInPlace && !isFileInTrashStorageDir) {
            Log.w(TAG,
                    "Restoration denied: Trashed file is neither in-place nor in the "
                            + ".trash-storage location. Path: "
                            + trashedFile.getAbsolutePath());
            return false;
        }
        // trashed file volume path should be equal to target volume path
        if (!trashedFileVolumePath.equals(targetParentVolumePath)) {
            Log.w(TAG, "Trashed file volume path not equal to target volume path");
            return false;
        }

        if (FileUtils.shouldBeInvisible(targetParent.getParent())) {
            Log.w(TAG, "Cannot restored to restricted path");
            return false;
        }

        return true;
    }

    /**
     * Recursively removes a prefix from the names of all children in a directory.
     *
     * @param dir            the directory whose children need to be unprefixed.
     * @param originalPrefix the prefix to remove (e.g., ".trashed-TIMESTAMP-").
     * @return 0 on success, or an errno value on failure.
     */
    private static int restoreChildrenOnDisk(File dir, String originalPrefix) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            String childName = child.getName();
            if (childName.startsWith(originalPrefix)) {
                String newName = childName.substring(originalPrefix.length());
                File updatedFile = new File(child.getParent(), newName);
                try {
                    Os.rename(child.getAbsolutePath(), updatedFile.getAbsolutePath());
                    if (updatedFile.isDirectory()) {
                        // Recurse for subdirectories
                        int errNo = restoreChildrenOnDisk(updatedFile, originalPrefix);
                        if (errNo != 0) {
                            return errNo;
                        }
                    }
                } catch (ErrnoException e) {
                    Log.e(TAG, "Failed to unprefix child: " + child.getAbsolutePath(), e);
                    return e.errno;
                }
            }
        }

        return 0;
    }

}
