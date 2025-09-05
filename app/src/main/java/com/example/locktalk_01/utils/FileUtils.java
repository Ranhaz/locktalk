
package com.example.locktalk_01.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class FileUtils {
    public static byte[] readBytesFromFile(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) throw new IOException("File not found: " + path);
        long len = file.length();
        if (len > Integer.MAX_VALUE) throw new IOException("File too large: " + path);
        byte[] bytes = new byte[(int) len];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = in.read(bytes);
            if (read != len) throw new IOException("Could not read entire file");
        }
        return bytes;
    }
}
