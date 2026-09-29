package com.kratisai.controlplane.model;

import java.util.regex.Pattern;

/** Single place for printable-char trimming of command roots and segments. */
public final class CommandText {

    private static final Pattern ANSI =
            Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*(\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]");

    private CommandText() {}

    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String withoutAnsi = ANSI.matcher(value).replaceAll("");
        StringBuilder cleaned = new StringBuilder(withoutAnsi.length());
        for (int i = 0; i < withoutAnsi.length(); ) {
            int cp = withoutAnsi.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                cleaned.append(' ');
            } else if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) {
                // drop C0/C1 controls and DEL
            } else {
                cleaned.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return cleaned.toString().replaceAll("\\s+", " ").strip();
    }
}
