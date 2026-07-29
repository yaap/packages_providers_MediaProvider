/*
 * Copyright (C) 2026 The Android Open Source Project
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utility to detect WebP features by parsing RIFF headers without decoding image data.
 */
public final class WebpDetectorUtils {
    private static final String RIFF_SIG = "RIFF";
    private static final String WEBP_SIG = "WEBP";
    private static final String VP8X_CHUNK = "VP8X";
    private static final String VP8_CHUNK = "VP8 ";
    private static final String VP8L_CHUNK = "VP8L";

    // Header and Chunk constants
    private static final int RIFF_HEADER_SIZE = 12;
    private static final int CHUNK_HEADER_SIZE = 8;
    private static final int FOURCC_SIZE = 4;
    private static final int WEBP_ID_OFFSET = 8;
    private static final int CHUNK_SIZE_OFFSET = 4;

    // Animation flag is at bit 1 (0x02) in the VP8X flags byte
    private static final int VP8X_ANIMATION_FLAG = 0x02;

    private static final int SKIP_BUFFER_SIZE = 4096;

    private WebpDetectorUtils() {
    }

    /**
     * Checks if a WebP file is animated by inspecting the VP8X header bit.
     * This avoids full bitmap decodes and massive native memory churn.
     *
     * @param file The WebP file to inspect.
     * @return true if the file is an animated WebP, false otherwise.
     */
    public static boolean isAnimatedWebp(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] header = new byte[RIFF_HEADER_SIZE];
            if (readFully(in, header) != RIFF_HEADER_SIZE) {
                return false;
            }

            // Verify "RIFF" and "WEBP" signatures
            if (!RIFF_SIG.equals(new String(header, 0, FOURCC_SIZE, StandardCharsets.US_ASCII))
                    || !WEBP_SIG.equals(new String(header, WEBP_ID_OFFSET, FOURCC_SIZE,
                    StandardCharsets.US_ASCII))) {
                return false;
            }

            long riffSize = getUint32(header, CHUNK_SIZE_OFFSET);
            // After 'RIFF' and size, there's 'WEBP' (4 bytes), then chunks start.
            // riffSize is the size of the data following the 8-byte 'RIFF' + size header.
            long bytesRemaining = riffSize - 4;

            byte[] chunkHeader = new byte[CHUNK_HEADER_SIZE];
            while (bytesRemaining >= CHUNK_HEADER_SIZE
                    && readFully(in, chunkHeader) == CHUNK_HEADER_SIZE) {
                bytesRemaining -= CHUNK_HEADER_SIZE;
                String fourCC = new String(chunkHeader, 0, FOURCC_SIZE, StandardCharsets.US_ASCII);
                long chunkSize = getUint32(chunkHeader, CHUNK_SIZE_OFFSET);

                if (chunkSize > bytesRemaining) {
                    // Invalid chunkSize
                    return false;
                }

                if (VP8X_CHUNK.equals(fourCC)) {
                    int flags = in.read();
                    if (flags == -1) {
                        return false;
                    }
                    return (flags & VP8X_ANIMATION_FLAG) != 0;
                } else if (VP8_CHUNK.equals(fourCC) || VP8L_CHUNK.equals(fourCC)) {
                    return false;
                }

                // Chunks are padded to 2-byte boundaries
                long skipSize = chunkSize + (chunkSize % 2);
                if (skipSize > bytesRemaining) {
                    skipSize = bytesRemaining;
                }
                skipFully(in, skipSize);
                bytesRemaining -= skipSize;
            }
        } catch (IOException e) {
            // Fall back to false on IO errors to prevent scanner stalls
        }
        return false;
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int total = 0;
        while (total < b.length) {
            int result = in.read(b, total, b.length - total);
            if (result == -1) {
                break;
            }
            total += result;
        }
        return total;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] buf = null;
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                // Use a temporary buffer for efficient reads when skip() makes no progress
                int skip = (int) Math.min(n, SKIP_BUFFER_SIZE);
                if (buf == null) {
                    buf = new byte[skip];
                }
                int read = in.read(buf, 0, skip);
                if (read == -1) {
                    throw new IOException("Unexpected EOF");
                }
                n -= read;
            } else {
                n -= skipped;
            }
        }
    }

    private static long getUint32(byte[] b, int offset) {
        return ((b[offset + 3] & 0xFFL) << 24)
                | ((b[offset + 2] & 0xFFL) << 16)
                | ((b[offset + 1] & 0xFFL) << 8)
                | (b[offset] & 0xFFL);
    }
}
