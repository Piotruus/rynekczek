package pl.rynek;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class Category {
    public static final class Sub {
        public final String id, name;
        public final boolean all;
        public final ItemMatcher matcher;
        Sub(String id, String name, boolean all, ItemMatcher matcher) {
            this.id = id; this.name = name; this.all = all; this.matcher = matcher;
        }
    }

    public final String id, title, color;
    public final Material icon;
    public final List<Sub> subs = new ArrayList<>();

    private Category(String id, String title, String color, Material icon) {
        this.id = id; this.title = title; this.color = color; this.icon = icon;
    }

    public static List<Category> load(ConfigurationSection root) {
        List<Category> out = new ArrayList<>();
        if (root == null) return out;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Material icon = Material.matchMaterial(s.getString("icon", "CHEST"));
            Category c = new Category(id, s.getString("title", id.toUpperCase()), s.getString("color", "&f"),
                    icon == null ? Material.CHEST : icon);
            ConfigurationSection subs = s.getConfigurationSection("subcategories");
            if (subs != null) {
                for (String sid : subs.getKeys(false)) {
                    ConfigurationSection ss = subs.getConfigurationSection(sid);
                    if (ss == null) continue;
                    c.subs.add(new Sub(sid, ss.getString("name", sid), ss.getBoolean("all", false),
                            ItemMatcher.parse(ss.getConfigurationSection("match"))));
                }
            }
            if (c.subs.isEmpty()) c.subs.add(new Sub("wszystko", "Wszystko", true, ItemMatcher.parse(null)));
            out.add(c);
        }
        return out;
    }

    public boolean matches(Listing l, int subIdx) {
        subIdx = Math.max(0, Math.min(subIdx, subs.size() - 1));
        String key = id + ":" + subIdx;
        Boolean b = l.cache.get(key);
        if (b == null) {
            b = compute(l.item, subIdx);
            l.cache.put(key, b);
        }
        return b;
    }

    private boolean compute(ItemStack it, int idx) {
        Sub s = subs.get(idx);
        if (!s.all) return s.matcher.matches(it);
        if (s.matcher.matches(it)) return true;
        for (Sub o : subs) if (!o.all && o.matcher.matches(it)) return true;
        return false;
    }
}
