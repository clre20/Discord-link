package clre20.discordLink.render;

/** 同一種樣式的一段文字。 */
public record TextRun(String text, int rgb, boolean bold, boolean italic, boolean underline, boolean strikethrough) {
}
