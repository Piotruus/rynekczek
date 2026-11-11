package pl.rynek;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class ListingManager {
    private final RynekPlugin plugin;
    private final File file;
    private final Map<UUID, Listing> map = new LinkedHashMap<>();
    private final Map<UUID, Integer> pendingSold = new HashMap<>();
    private boolean dirty;

    public ListingManager(RynekPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ls = y.getConfigurationSection("listings");
        if (ls != null) {
            for (String key : ls.getKeys(false)) {
                try {
                    ConfigurationSection s = ls.getConfigurationSection(key);
                    ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(s.getString("item", "")));
                    Listing l = new Listing(UUID.fromString(key), UUID.fromString(s.getString("seller", "")),
                            s.getString("sellerName", "?"), item, s.getDouble("price"),
                            s.getLong("created"), s.getLong("expires"));
                    map.put(l.id, l);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Nie można wczytać oferty " + key + ": " + ex.getMessage());
                }
            }
        }
        ConfigurationSection ps = y.getConfigurationSection("pending");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                try { pendingSold.put(UUID.fromString(key), ps.getInt(key)); } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    public synchronized void save() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            for (Listing l : map.values()) {
                String p = "listings." + l.id;
                y.set(p + ".seller", l.seller.toString());
                y.set(p + ".sellerName", l.sellerName);
                y.set(p + ".price", l.price);
                y.set(p + ".created", l.created);
                y.set(p + ".expires", l.expires);
                y.set(p + ".item", Base64.getEncoder().encodeToString(l.item.serializeAsBytes()));
            }
            for (Map.Entry<UUID, Integer> e : pendingSold.entrySet()) y.set("pending." + e.getKey(), e.getValue());
            plugin.getDataFolder().mkdirs();
            File tmp = new File(plugin.getDataFolder(), "data.yml.tmp");
            y.save(tmp);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (Exception ex) {
            plugin.getLogger().severe("Nie udało się zapisać data.yml: " + ex.getMessage());
        }
    }

    public void saveIfDirty() { if (dirty) save(); }

    public void add(Listing l) { map.put(l.id, l); dirty = true; }
    public Listing remove(UUID id) { Listing l = map.remove(id); if (l != null) dirty = true; return l; }
    public Listing get(UUID id) { return map.get(id); }
    public Collection<Listing> all() { return map.values(); }

    public List<Listing> active() {
        List<Listing> out = new ArrayList<>();
        for (Listing l : map.values()) if (!l.isExpired()) out.add(l);
        return out;
    }
    public List<Listing> activeOf(UUID u) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : map.values()) if (l.seller.equals(u) && !l.isExpired()) out.add(l);
        return out;
    }
    public List<Listing> expiredOf(UUID u) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : map.values()) if (l.seller.equals(u) && l.isExpired()) out.add(l);
        return out;
    }

    public void addPending(UUID u) { pendingSold.merge(u, 1, Integer::sum); dirty = true; }
    public int takePending(UUID u) {
        Integer n = pendingSold.remove(u);
        if (n != null) dirty = true;
        return n == null ? 0 : n;
    }

    public void clearCaches() {
        for (Listing l : map.values()) l.cache.clear();
    }
}
