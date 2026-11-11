package pl.rynek;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/** Reguły rozpoznawania przedmiotów z config.yml (wystarczy spełnić jedną). */
public final class ItemMatcher {
    private final Set<Material> materials = EnumSet.noneOf(Material.class);
    private final List<String> suffix = new ArrayList<>(), contains = new ArrayList<>();
    private final List<String> name = new ArrayList<>(), lore = new ArrayList<>(), text = new ArrayList<>(), allText = new ArrayList<>();
    private final Set<String> pdc = new HashSet<>();

    public static ItemMatcher parse(ConfigurationSection s) {
        ItemMatcher m = new ItemMatcher();
        if (s == null) return m;
        for (String x : s.getStringList("materials")) {
            Material mat = Material.matchMaterial(x);
            if (mat != null) m.materials.add(mat);
        }
        for (String x : s.getStringList("suffix")) m.suffix.add(x.toUpperCase(Locale.ROOT));
        for (String x : s.getStringList("contains")) m.contains.add(x.toUpperCase(Locale.ROOT));
        for (String x : s.getStringList("name")) m.name.add(Text.norm(x));
        for (String x : s.getStringList("lore")) m.lore.add(Text.norm(x));
        for (String x : s.getStringList("text")) m.text.add(Text.norm(x));
        for (String x : s.getStringList("all-text")) m.allText.add(Text.norm(x));
        for (String x : s.getStringList("pdc")) m.pdc.add(x.toLowerCase(Locale.ROOT));
        return m;
    }

    public boolean matches(ItemStack it) {
        if (materials.contains(it.getType())) return true;
        String mn = it.getType().name();
        for (String s : suffix) if (mn.endsWith(s)) return true;
        for (String s : contains) if (mn.contains(s)) return true;
        if (name.isEmpty() && lore.isEmpty() && text.isEmpty() && allText.isEmpty() && pdc.isEmpty()) return false;

        ItemMeta m = it.getItemMeta();
        if (m == null) return false;
        String n = m.hasDisplayName() ? Text.norm(Text.plain(m.displayName())) : "";
        StringBuilder lb = new StringBuilder();
        if (m.hasLore() && m.lore() != null) m.lore().forEach(c -> lb.append(Text.norm(Text.plain(c))).append(' '));
        String l = lb.toString();
        String both = n + " " + l;

        for (String s : name) if (n.contains(s)) return true;
        for (String s : lore) if (l.contains(s)) return true;
        for (String s : text) if (both.contains(s)) return true;
        if (!allText.isEmpty()) {
            boolean ok = true;
            for (String s : allText) if (!both.contains(s)) { ok = false; break; }
            if (ok) return true;
        }
        if (!pdc.isEmpty()) {
            for (NamespacedKey k : m.getPersistentDataContainer().getKeys()) {
                if (pdc.contains(k.toString().toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }
}
