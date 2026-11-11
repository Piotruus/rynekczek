package pl.rynek;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class RynekPlugin extends JavaPlugin implements Listener {
    private Economy economy;
    private ListingManager listings;
    private MarketService market;
    private RynekGui gui;
    private List<Category> categories = new ArrayList<>();
    private final Set<Material> blacklist = EnumSet.noneOf(Material.class);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        listings = new ListingManager(this);
        listings.load();
        reloadSettings();
        market = new MarketService(this);
        gui = new RynekGui(this);

        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(this, this);

        RynekCommand cmd = new RynekCommand(this);
        getCommand("ah").setExecutor(cmd);
        getCommand("ah").setTabCompleter(cmd);

        if (economy() == null) getLogger().warning("Nie znaleziono ekonomii Vault! Zainstaluj plugin ekonomii (np. EssentialsX).");

        // wygasanie ofert + powiadomienia
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Listing l : listings.all()) {
                if (l.isExpired() && !l.expiryNotified) {
                    l.expiryNotified = true;
                    Player p = Bukkit.getPlayer(l.seller);
                    if (p != null) send(p, "expired-notify");
                }
            }
        }, 200L, 200L);
        // autozapis
        Bukkit.getScheduler().runTaskTimer(this, listings::saveIfDirty, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        if (listings != null) listings.save();
    }

    public void reloadSettings() {
        reloadConfig();
        Text.currency = getConfig().getString("currency-symbol", "$");
        blacklist.clear();
        for (String s : getConfig().getStringList("blacklist")) {
            Material m = Material.matchMaterial(s);
            if (m != null) blacklist.add(m);
        }
        categories = Category.load(getConfig().getConfigurationSection("categories"));
        if (listings != null) listings.clearCaches();
    }

    public Economy economy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        return economy;
    }

    public ListingManager listings() { return listings; }
    public MarketService market() { return market; }
    public RynekGui gui() { return gui; }
    public List<Category> categories() { return categories; }
    public boolean isBlacklisted(Material m) { return blacklist.contains(m); }

    public Category category(String id) {
        for (Category c : categories) if (c.id.equals(id)) return c;
        return null;
    }

    public String raw(String key, String... repl) {
        String s = getConfig().getString("messages." + key, "&c[brak wiadomości: " + key + "]");
        for (int i = 0; i + 1 < repl.length; i += 2) s = s.replace(repl[i], repl[i + 1]);
        return s;
    }

    public void send(CommandSender to, String key, String... repl) {
        String s = raw(key, repl);
        if (!s.isEmpty()) to.sendMessage(Text.c(s));
    }

    public int limitFor(Player p) {
        int best = getConfig().getInt("default-max-listings", 10);
        for (PermissionAttachmentInfo i : p.getEffectivePermissions()) {
            String perm = i.getPermission();
            if (i.getValue() && perm.startsWith("rynek.limit.")) {
                try { best = Math.max(best, Integer.parseInt(perm.substring(12))); } catch (NumberFormatException ignored) {}
            }
        }
        return best;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            int sold = listings.takePending(p.getUniqueId());
            if (sold > 0) {
                if (sold <= 3) for (int i = 0; i < sold; i++) send(p, "sold");
                else send(p, "sold-offline", "{count}", String.valueOf(sold));
            }
            int exp = listings.expiredOf(p.getUniqueId()).size();
            if (exp > 0) send(p, "expired-join", "{count}", String.valueOf(exp));
        }, 40L);
    }
}
