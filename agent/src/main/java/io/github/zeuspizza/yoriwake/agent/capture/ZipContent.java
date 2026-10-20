package io.github.zeuspizza.yoriwake.agent.capture;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Whether a file is a zip archive by its content. The agent records a test's reads of an archive by
 * this test, and the plugin decides by it whether a changed file can be traced to those reads, so
 * both sides tell an archive the same way.
 */
public final class ZipContent {

    /** The length of a zip's end record with no comment. */
    static final int END_RECORD = 22;

    private ZipContent() {}

    /**
     * Whether [file] starts with an entry or an empty archive's end record, or ends with an end
     * record, as an archive behind a launch script does. Only a regular file is read, so a pipe or a
     * device is never drained. False when it cannot be read.
     */
    public static boolean holdsAZip(File file) {
        try {
            if (!file.isFile() || file.length() < END_RECORD) {
                return false;
            }
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                byte[] signature = new byte[4];
                in.readFully(signature);
                if (startsAZip(signature)) {
                    return true;
                }
                in.seek(file.length() - END_RECORD);
                in.readFully(signature);
                return endsAZip(signature);
            }
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    static boolean startsAZip(byte[] b) {
        return b.length == 4 && b[0] == 'P' && b[1] == 'K'
                && ((b[2] == 3 && b[3] == 4) || (b[2] == 5 && b[3] == 6));
    }

    private static boolean endsAZip(byte[] b) {
        return b.length == 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 5 && b[3] == 6;
    }
}
