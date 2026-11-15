package clre20.discordLink.render;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.ScoreComponent;
import net.kyori.adventure.text.SelectorComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 把 Adventure 文字（含可翻譯文字）攤平成有樣式的片段，或純文字。
 * 翻譯用下載的原版語言檔，所以 Discord 看到的物品名稱跟遊戲裡的語言一致。
 */
public final class TextFlattener {

    private static final int MAX_DEPTH = 16;
    private static final int MAX_CHARS = 4000;

    private final Function<String, String> translator;

    /** @param translator 翻譯鍵 → 文字，找不到回傳 null */
    public TextFlattener(Function<String, String> translator) {
        this.translator = translator;
    }

    public String plain(Component c) {
        StringBuilder sb = new StringBuilder();
        for (TextRun r : runs(c, 0xFFFFFF)) {
            sb.append(r.text());
        }
        return sb.toString();
    }

    public List<TextRun> runs(Component c, int defaultRgb) {
        List<TextRun> out = new ArrayList<>();
        walk(c, Style.empty().color(net.kyori.adventure.text.format.TextColor.color(defaultRgb)), out, 0, new int[]{0});
        return out;
    }

    private void walk(Component c, Style parent, List<TextRun> out, int depth, int[] count) {
        if (depth > MAX_DEPTH || count[0] > MAX_CHARS) {
            return;
        }
        // 子層有設定的樣式覆蓋父層，沒設定的沿用父層
        Style style = merged(parent, c.style());
        if (c instanceof TextComponent t) {
            emit(t.content(), style, out, count);
        } else if (c instanceof TranslatableComponent t) {
            translate(t, style, out, depth, count);
        } else if (c instanceof KeybindComponent k) {
            emit(k.keybind(), style, out, count);
        } else if (c instanceof ScoreComponent s) {
            emit(s.name(), style, out, count);
        } else if (c instanceof SelectorComponent s) {
            emit(s.pattern(), style, out, count);
        }
        for (Component child : c.children()) {
            walk(child, style, out, depth + 1, count);
        }
    }

    private static Style merged(Style parent, Style child) {
        Style.Builder b = parent.toBuilder();
        if (child.color() != null) {
            b.color(child.color());
        }
        for (TextDecoration d : TextDecoration.values()) {
            TextDecoration.State st = child.decoration(d);
            if (st != TextDecoration.State.NOT_SET) {
                b.decoration(d, st);
            }
        }
        return b.build();
    }

    private void translate(TranslatableComponent t, Style style, List<TextRun> out, int depth, int[] count) {
        String fmt = translator.apply(t.key());
        if (fmt == null) {
            fmt = t.fallback() != null ? t.fallback() : t.key();
        }
        List<TranslationArgument> args = t.arguments();
        int next = 0;
        StringBuilder lit = new StringBuilder();
        for (int i = 0; i < fmt.length(); i++) {
            char ch = fmt.charAt(i);
            if (ch != '%' || i + 1 >= fmt.length()) {
                lit.append(ch);
                continue;
            }
            char n = fmt.charAt(i + 1);
            if (n == '%') {
                lit.append('%');
                i++;
                continue;
            }
            int idx = -1;
            int end = i + 1;
            if (n == 's' || n == 'd') {
                idx = next++;
                end = i + 2;
            } else if (Character.isDigit(n)) {
                int j = i + 1;
                while (j < fmt.length() && Character.isDigit(fmt.charAt(j))) {
                    j++;
                }
                if (j + 1 < fmt.length() && fmt.charAt(j) == '$' && (fmt.charAt(j + 1) == 's' || fmt.charAt(j + 1) == 'd')) {
                    idx = Integer.parseInt(fmt.substring(i + 1, j)) - 1;
                    end = j + 2;
                }
            }
            if (idx < 0) {
                lit.append(ch);
                continue;
            }
            emit(lit.toString(), style, out, count);
            lit.setLength(0);
            if (idx < args.size()) {
                walk(args.get(idx).asComponent(), style, out, depth + 1, count);
            }
            i = end - 1;
        }
        emit(lit.toString(), style, out, count);
    }

    private static void emit(String text, Style style, List<TextRun> out, int[] count) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (count[0] + text.length() > MAX_CHARS) {
            text = text.substring(0, Math.max(0, MAX_CHARS - count[0]));
        }
        count[0] += text.length();
        int rgb = style.color() == null ? 0xFFFFFF : style.color().value();
        out.add(new TextRun(text, rgb,
                style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE,
                style.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE,
                style.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE,
                style.decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE));
    }
}
