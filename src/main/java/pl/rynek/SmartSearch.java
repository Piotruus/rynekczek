package pl.rynek;

import net.kyori.adventure.text.Component;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;

import java.util.*;

/**
 * Inteligentne wyszukiwanie:
 *  - ignoruje wielkość liter i polskie znaki ("łopata" = "lopata"),
 *  - rozumie polskie nazwy ("diamentowy miecz ostrość" -> diamond sword sharpness),
 *  - toleruje literówki i odmiany, dopasowuje początki słów ("kil" -> kilof),
 *  - przeszukuje nazwę, lore, materiał, enchanty, sprzedającego,
 *  - filtry cen: "<5000" (do) oraz ">100" (od), np. "kilof >1k <10k",
 *  - wyniki sortowane trafnością (gdy sortowanie = "Brak motywu").
 */
public final class SmartSearch {
    private static final Map<String, String[]> DICT = new LinkedHashMap<>();

    static {
        d("miecz", "sword"); d("kilof", "pickaxe"); d("lopat", "shovel"); d("siekier", "axe"); d("topor", "axe");
        d("motyk", "hoe"); d("luk", "bow"); d("kusz", "crossbow"); d("trojz", "trident"); d("buzdygan", "mace");
        d("helm", "helmet"); d("naplers", "chestplate"); d("kiras", "chestplate"); d("spodni", "leggings");
        d("but", "boots"); d("elytr", "elytra"); d("skrzydl", "elytra"); d("tarcz", "shield");
        d("diament", "diamond"); d("zelaz", "iron"); d("zlot", "golden", "gold"); d("netheryt", "netherite");
        d("kamien", "stone"); d("drewn", "wooden"); d("skor", "leather"); d("kolcz", "chainmail");
        d("szmaragd", "emerald"); d("wegiel", "coal"); d("jablk", "apple"); d("ksieg", "book"); d("mikstur", "potion");
        d("ostros", "sharpness"); d("ochron", "protection"); d("nietykal", "unbreaking"); d("niezniszcz", "unbreaking");
        d("grabiez", "looting"); d("fortun", "fortune"); d("wydajn", "efficiency"); d("napraw", "mending");
        d("odrzut", "knockback"); d("ogn", "fire"); d("moc", "power"); d("ciern", "thorns"); d("piork", "feather");
        d("nieskoncz", "infinity"); d("jedwab", "silk"); d("dotyk", "touch"); d("szybk", "swift", "speed");
    }

    private static void d(String pl, String... en) { DICT.put(pl, en); }

    public static final class Query {
        final List<List<String>> terms = new ArrayList<>();
        double min = -1, max = -1;
    }

    public static Query parse(String raw) {
        Query q = new Query();
        for (String part : raw.trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            if (part.length() > 1 && (part.charAt(0) == '<' || part.charAt(0) == '>')) {
                double v = Text.parsePrice(part.substring(1).replace("=", ""));
                if (v >= 0) {
                    if (part.charAt(0) == '<') q.max = v; else q.min = v;
                    continue;
                }
            }
            for (String t : Text.norm(part).split("[^a-z0-9]+")) {
                if (!t.isEmpty()) q.terms.add(expand(t));
            }
        }
        return q;
    }

    private static List<String> expand(String t) {
        List<String> out = new ArrayList<>();
        out.add(t);
        if (t.matches("\\d+")) return out;
        for (Map.Entry<String, String[]> e : DICT.entrySet()) {
            String k = e.getKey();
            boolean inflected = t.startsWith(k) && t.length() <= k.length() + 4;
            boolean partial = t.length() >= 3 && k.startsWith(t);
            if (inflected || partial) out.addAll(Arrays.asList(e.getValue()));
        }
        return out;
    }

    /** -1 = brak dopasowania, w innym przypadku wynik trafności. */
    public static double score(Listing l, Query q) {
        if (q.min >= 0 && l.price < q.min) return -1;
        if (q.max >= 0 && l.price > q.max) return -1;
        Set<String> hay = tokens(l);
        double total = 0;
        for (List<String> alts : q.terms) {
            double best = 0;
            for (String a : alts) for (String h : hay) best = Math.max(best, match(h, a));
            if (best == 0) return -1;
            total += best;
        }
        return total + 1;
    }

    private static double match(String h, String a) {
        if (h.equals(a)) return 3;
        if (a.matches("\\d+")) return 0;
        if (h.startsWith(a)) return 2.2;
        if (a.length() >= 3 && h.contains(a)) return 1.5;
        if (a.length() >= 4 && Math.abs(h.length() - a.length()) <= 2
                && lev(h, a) <= (a.length() >= 8 ? 2 : 1)) return 1.0;
        return 0;
    }

    private static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static Set<String> tokens(Listing l) {
        if (l.tokens != null) return l.tokens;
        Set<String> set = new HashSet<>();
        ItemStack it = l.item;
        add(set, it.getType().name());
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            if (m.hasDisplayName()) add(set, Text.plain(m.displayName()));
            if (m.hasLore() && m.lore() != null) for (Component c : m.lore()) add(set, Text.plain(c));
            enchants(set, m.getEnchants());
            if (m instanceof EnchantmentStorageMeta esm) enchants(set, esm.getStoredEnchants());
            if (m instanceof PotionMeta pm && pm.getBasePotionType() != null) add(set, pm.getBasePotionType().name());
        }
        add(set, l.sellerName);
        l.tokens = set;
        return set;
    }

    private static void enchants(Set<String> set, Map<Enchantment, Integer> map) {
        for (Map.Entry<Enchantment, Integer> e : map.entrySet()) {
            add(set, e.getKey().getKey().getKey());
            set.add(String.valueOf(e.getValue()));
            set.add(roman(e.getValue()));
        }
    }

    private static String roman(int n) {
        String[] r = {"", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"};
        return n >= 1 && n <= 10 ? r[n] : String.valueOf(n);
    }

    private static void add(Set<String> set, String text) {
        for (String t : Text.norm(text).split("[^a-z0-9]+")) if (!t.isEmpty()) set.add(t);
    }
}
