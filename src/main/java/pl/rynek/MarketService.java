package pl.rynek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.time.Duration;
import java.util.UUID;

public final class MarketService {
    private final RynekPlugin plugin;

    public MarketService(RynekPlugin plugin) { this.plugin = plugin; }

    public void sell(Player p, double price) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { plugin.send(p, "hand-empty"); return; }
        if (plugin.isBlacklisted(hand.getType())) { plugin.send(p, "blacklisted"); return; }
        double min = plugin.getConfig().getDouble("min-price", 1);
        double max = plugin.getConfig().getDouble("max-price", 1e12);
        if (price < min) { plugin.send(p, "price-too-low", "{min}", Text.money(min)); return; }
        if (price > max) { plugin.send(p, "price-too-high", "{max}", Text.money(max)); return; }
        int limit = plugin.limitFor(p);
        if (plugin.listings().activeOf(p.getUniqueId()).size() >= limit) {
            plugin.send(p, "limit-reached", "{limit}", String.valueOf(limit));
            return;
        }
        long now = System.currentTimeMillis();
        long dur = (long) (plugin.getConfig().getDouble("listing-duration-hours", 24) * 3600_000L);
        Listing l = new Listing(UUID.randomUUID(), p.getUniqueId(), p.getName(), hand.clone(), price, now, now + dur);
        p.getInventory().setItemInMainHand(null);
        plugin.listings().add(l);

        // napis na środku ekranu (zielony)
        p.showTitle(Title.title(Text.c(plugin.raw("listed-title")), Component.empty(),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))));
        plugin.gui().refreshAll();
    }

    public boolean buy(Player buyer, UUID id) {
        Listing l = plugin.listings().get(id);
        if (l == null || l.isExpired()) { plugin.send(buyer, "gone"); return false; }
        if (l.seller.equals(buyer.getUniqueId())) { plugin.send(buyer, "own-listing"); return false; }
        Economy eco = plugin.economy();
        if (eco == null) { plugin.send(buyer, "error"); return false; }
        if (!eco.has(buyer, l.price)) { plugin.send(buyer, "not-enough-money", "{price}", Text.money(l.price)); return false; }
        if (!hasSpace(buyer.getInventory(), l.item)) { plugin.send(buyer, "no-space"); return false; }

        EconomyResponse r = eco.withdrawPlayer(buyer, l.price);
        if (!r.transactionSuccess()) { plugin.send(buyer, "error"); return false; }
        plugin.listings().remove(id);
        buyer.getInventory().addItem(l.item.clone()).values()
                .forEach(left -> buyer.getWorld().dropItemNaturally(buyer.getLocation(), left));

        double feePct = plugin.getConfig().getDouble("fee-percent", 3.0);
        double fee = Math.round(l.price * feePct) / 100.0;
        double payout = Math.max(0, l.price - fee);
        OfflinePlayer seller = Bukkit.getOfflinePlayer(l.seller);
        EconomyResponse dep = eco.depositPlayer(seller, payout);
        if (!dep.transactionSuccess()) plugin.getLogger().severe("Nie udało się wypłacić " + payout + " graczowi " + l.sellerName);

        Player sp = Bukkit.getPlayer(l.seller);
        if (sp != null) {
            plugin.send(sp, "sold");
            plugin.send(sp, "sold-details", "{amount}", Text.money(payout), "{fee}", trim(feePct));
        } else {
            plugin.listings().addPending(l.seller);
        }
        plugin.send(buyer, "bought", "{item}", Text.itemName(l.item), "{price}", Text.money(l.price));
        plugin.gui().refreshAll();
        return true;
    }

    /** Wycofanie aktywnej oferty lub odebranie wygasłej. */
    public boolean take(Player p, UUID id) {
        Listing l = plugin.listings().get(id);
        if (l == null || !l.seller.equals(p.getUniqueId())) { plugin.send(p, "gone"); return false; }
        if (!hasSpace(p.getInventory(), l.item)) { plugin.send(p, "no-space"); return false; }
        boolean expired = l.isExpired();
        plugin.listings().remove(id);
        p.getInventory().addItem(l.item.clone()).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        plugin.send(p, expired ? "claimed" : "withdrawn");
        plugin.gui().refreshAll();
        return true;
    }

    private static boolean hasSpace(PlayerInventory inv, ItemStack it) {
        int need = it.getAmount();
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) return true;
            if (s.isSimilar(it)) need -= (s.getMaxStackSize() - s.getAmount());
            if (need <= 0) return true;
        }
        return need <= 0;
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
