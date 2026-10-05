package net.minecraft.text;
/** Isolated lifecycle fixture, never included in production compilation or packaging. */
public final class Text {
    private Text() {}
    public static Text literal(String value) { return new Text(); }
}
