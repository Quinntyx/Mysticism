package io.github.mysticism.embedding;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;

/** Unicode 17 extended graphemes (UAX #29), pinned rather than JDK-version dependent. */
final class UnicodeGraphemes {
    private static final int CR = 1, LF = 2, CONTROL = 3, EXTEND = 4, ZWJ = 5,
            RI = 6, PREPEND = 7, SPACING = 8, L = 9, V = 10, T = 11, LV = 12, LVT = 13;
    private static final int PICTOGRAPHIC = 16, CONSONANT = 32, LINKER = 64, INCB_EXTEND = 128;
    private static final int SCALARS = 0x110000;
    private static final String SHA256 = "23a1412313b4e74dbb1718ee448cfadd8c8a1ef262400b1b151be3590ab09d94";
    private final byte[] properties;

    UnicodeGraphemes() throws IOException {
        var resource = UnicodeGraphemes.class.getResourceAsStream("/assets/mysticism/embedding/unicode-17-graphemes.bin.gz");
        if (resource == null) throw new IOException("Missing pinned tokenizer Unicode data");
        try (var input = new GZIPInputStream(resource)) {
            properties = input.readNBytes(SCALARS + 1);
        }
        if (properties.length != SCALARS) throw new IOException("Invalid tokenizer Unicode data length");
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(properties));
            if (!SHA256.equals(digest)) throw new IOException("Tokenizer Unicode data SHA256 mismatch");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    /** Input has already passed scalar validation; start is a known grapheme boundary. */
    int nextBoundary(String text, int start) {
        if (start == text.length()) return start;
        int cp = text.codePointAt(start), previous = property(cp);
        int regionalCount = (previous & 15) == RI ? 1 : 0;
        for (int at = start + Character.charCount(cp); at < text.length();) {
            cp = text.codePointAt(at);
            int current = property(cp);
            if (breaks(text, start, at, previous, current, regionalCount)) return at;
            regionalCount = (current & 15) == RI ? regionalCount + 1 : 0;
            previous = current;
            at += Character.charCount(cp);
        }
        return text.length();
    }

    private boolean breaks(String text, int start, int at, int previous, int current, int regionalCount) {
        int before = previous & 15, after = current & 15;
        if (before == CR && after == LF) return false; // GB3
        if (control(before) || control(after)) return true; // GB4, GB5
        if (before == L && (after == L || after == V || after == LV || after == LVT)) return false; // GB6
        if ((before == LV || before == V) && (after == V || after == T)) return false; // GB7
        if ((before == LVT || before == T) && after == T) return false; // GB8
        if (after == EXTEND || after == ZWJ || after == SPACING || before == PREPEND) return false; // GB9..9b
        if ((current & CONSONANT) != 0) { // GB9c: consonant (extend|linker)* linker (extend|linker)*
            boolean linker = false;
            for (int back = at; back > start;) {
                int cp = text.codePointBefore(back);
                back -= Character.charCount(cp);
                int property = property(cp);
                if ((property & LINKER) != 0) linker = true;
                else if ((property & INCB_EXTEND) == 0) {
                    if (linker && (property & CONSONANT) != 0) return false;
                    break;
                }
            }
        }
        if (before == ZWJ && (current & PICTOGRAPHIC) != 0) { // GB11: pictographic Extend* ZWJ
            for (int back = at - 1; back > start;) { // ZWJ is one UTF-16 unit.
                int cp = text.codePointBefore(back);
                back -= Character.charCount(cp);
                int property = property(cp);
                if ((property & 15) == EXTEND) continue;
                if ((property & PICTOGRAPHIC) != 0) return false;
                break;
            }
        }
        if (before == RI && after == RI && regionalCount % 2 == 1) return false; // GB12, GB13
        return true; // GB999
    }

    private static boolean control(int category) { return category == CR || category == LF || category == CONTROL; }
    private int property(int cp) { return properties[cp] & 255; }
}
