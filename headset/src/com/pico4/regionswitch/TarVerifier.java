package com.pico4.regionswitch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read every byte and checksum every header; no extraction and no ignore-zero recovery. */
public final class TarVerifier {
    static void full(InputStream input, byte[] buffer) throws IOException {
        int done = 0, count;
        while (done < buffer.length && (count = input.read(buffer, done, buffer.length - done)) >= 0) done += count;
        if (done != buffer.length) throw new EOFException("Truncated TAR");
    }
    static String text(byte[] data, int offset, int count) {
        int end = offset; while (end < offset + count && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }
    static long octal(byte[] data, int offset, int count) {
        String value = text(data, offset, count).trim(); return value.isEmpty() ? 0 : Long.parseLong(value, 8);
    }
    public static int verify(File path, Set<String> roots) throws IOException {
        Set<String> seen = new HashSet<>(); int entries = 0, zeros = 0; byte[] block = new byte[512];
        try (InputStream stream = new BufferedInputStream(new FileInputStream(path))) {
            while (true) {
                full(stream, block); boolean empty = true; for (byte b : block) if (b != 0) { empty = false; break; }
                if (empty) {
                    if (++zeros < 2) continue;
                    int b; while ((b = stream.read()) != -1) if (b != 0) throw new IOException("Nonzero data after TAR terminator");
                    break;
                }
                if (zeros != 0) throw new IOException("Embedded TAR zero block");
                long sum = 0; for (int i = 0; i < 512; i++) sum += i >= 148 && i < 156 ? 32 : block[i] & 255;
                if (sum != octal(block, 148, 8)) throw new IOException("Bad TAR header checksum");
                String name = text(block, 0, 100), prefix = text(block, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                seen.add(name); entries++;
                long size = octal(block, 124, 12), blocks = (size + 511) / 512;
                for (long i = 0; i < blocks; i++) full(stream, block);
            }
        }
        if (!seen.containsAll(roots)) throw new IOException("Missing TAR application roots");
        return entries;
    }
}
