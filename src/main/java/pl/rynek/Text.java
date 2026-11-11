package pl.rynek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.util.Locale;

public final class Text {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    public static String currency = "$";

    private Text() {}

    /** Tekst z kodami &, bez domyślnej kursywy (lore/nazwy). */
    public static Component c(String s) {
        return LEGACY.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    public static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Małe litery, bez polskich znaków. */
    public static String norm(String s) {
        if (s == null) return "";
        s = s.toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    public static String itemName(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName()) return plain(m.displayName());
        StringBuilder sb = new StringBuilder();
        for (String w : it.getType().name().toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    public static String money(double v) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        sym.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0.##", sym).format(v) + currency;
    }

    public static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    /** Obsługuje 1000, 2.5k, 1,5m, 3b. Zwraca -1 przy błędzie. */
    public static double parsePrice(String in) {
        String s = in.trim().toLowerCase(Locale.ROOT).replace(',', '.').replace("_", "");
        double mult = 1;
        if (s.endsWith("k")) { mult = 1e3; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1e6; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("b")) { mult = 1e9; s = s.substring(0, s.length() - 1); }
        if (!s.matches("\\d+(\\.\\d+)?")) return -1;
        try {
            double v = Double.parseDouble(s) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v)) return -1;
            return Math.round(v * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
