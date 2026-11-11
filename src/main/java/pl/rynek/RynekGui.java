package pl.rynek;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.*;

public final class RynekGui implements Listener {
    public enum Mode { MARKET, ACTIVE, EXPIRED, CONFIRM }

    public enum Sort {
        NONE("Brak motywu"), PRICE_DESC("Najwyższej ceny"), PRICE_ASC("Najniższej ceny"),
        NEWEST("Najnowsze"), OLDEST("Najstarsze");
        final String label;
        Sort(String label) { this.label = label; }
        Sort next() { return values()[(ordinal() + 1) % values().length]; }
    }

    public static final class State {
        String category;
        int sub, page;
        Sort sort = Sort.NONE;
        String search;
    }

    public static final class Holder implements InventoryHolder {
        final Mode mode;
        Inventory inv;
        final Map<Integer, UUID> slots = new HashMap<>();
        UUID target;
        int pages = 1;
        Holder(Mode mode) { this.mode = mode; }
        @Override public Inventory getInventory() { return inv; }
    }

    // Układ: kolumna 0 = kategorie, kolumna 1 = przerwa, kolumny 2-8 x rzędy 0-3 = oferty
    static final int[] CAT_SLOTS = {0, 9, 18, 27};
    static final int[] ITEM_SLOTS = buildItemSlots();
    static final int S_ACTIVE = 45, S_EXPIRED = 46, S_SEARCH = 47, S_PREV = 48, S_CLOSE = 49, S_NEXT = 50, S_HELP = 52, S_SORT = 53;

    private static int[] buildItemSlots() {
        int[] a = new int[4 * 7];
        int i = 0;
        for (int r = 0; r < 4; r++) for (int c = 2; c <= 8; c++) a[i++] = r * 9 + c;
        return a;
    }

    private final RynekPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Long> awaitingSearch = new HashMap<>();

    public RynekGui(RynekPlugin plugin) { this.plugin = plugin; }

    private State state(Player p) { return states.computeIfAbsent(p.getUniqueId(), k -> new State()); }
    private String title() { return plugin.getConfig().getString("gui-title", "Rynek"); }

    // ===================== OTWIERANIE =====================

    public void openMarket(Player p, boolean fresh) {
        if (fresh) states.put(p.getUniqueId(), new State());
        Holder h = new Holder(Mode.MARKET);
        h.inv = Bukkit.createInventory(h, 54, Component.text(title()));
        render(p, h);
        p.openInventory(h.inv);
    }

    public void openPersonal(Player p, Mode mode) {
        Holder h = new Holder(mode);
        h.inv = Bukkit.createInventory(h, 54, Component.text(title()));
        renderPersonal(p, h);
        p.openInventory(h.inv);
    }

    private void openConfirm(Player p, Listing l) {
        Holder h = new Holder(Mode.CONFIRM);
        h.target = l.id;
        h.inv = Bukkit.createInventory(h, 27, Component.text("Potwierdź zakup"));
        ItemStack f = filler();
        for (int i = 0; i < 27; i++) h.inv.setItem(i, f);
        h.inv.setItem(11, button(Material.LIME_CONCRETE, "&aPotwierdź zakup", List.of("&7Cena: &a" + Text.money(l.price))));
        h.inv.setItem(13, display(l, List.of("", "&7Sprzedający: &f" + l.sellerName, "&7Cena: &a" + Text.money(l.price))));
        h.inv.setItem(15, button(Material.RED_CONCRETE, "&cAnuluj", List.of()));
        p.openInventory(h.inv);
    }

    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h) {
                if (h.mode == Mode.MARKET) render(p, h);
                else if (h.mode == Mode.ACTIVE || h.mode == Mode.EXPIRED) renderPersonal(p, h);
            }
        }
    }

    // ===================== RENDER =====================

    private void render(Player p, Holder h) {
        State st = state(p);
        Inventory inv = h.inv;
        h.slots.clear();
        ItemStack filler = filler();
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);
        for (int s : ITEM_SLOTS) inv.setItem(s, null);

        List<Category> cats = plugin.categories();
        List<Listing> active = plugin.listings().active();
        for (int i = 0; i < Math.min(4, cats.size()); i++) inv.setItem(CAT_SLOTS[i], categoryButton(cats.get(i), st, active));

        List<Listing> view = filter(st, active);
        int per = ITEM_SLOTS.length;
        int pages = Math.max(1, (view.size() + per - 1) / per);
        st.page = Math.max(0, Math.min(st.page, pages - 1));
        h.pages = pages;
        long now = System.currentTimeMillis();
        for (int i = 0; i < per; i++) {
            int idx = st.page * per + i;
            if (idx >= view.size()) break;
            Listing l = view.get(idx);
            boolean own = l.seller.equals(p.getUniqueId());
            inv.setItem(ITEM_SLOTS[i], display(l, List.of("",
                    "&7Sprzedający: &f" + l.sellerName,
                    "&7Cena: &a" + Text.money(l.price),
                    "&7Wygasa za: &e" + Text.duration(l.expires - now),
                    "",
                    own ? "&cTo Twoja oferta!" : "&aKliknij lewym, aby kupić!")));
            h.slots.put(ITEM_SLOTS[i], l.id);
        }

        // dolny rząd
        int myActive = plugin.listings().activeOf(p.getUniqueId()).size();
        int myExpired = plugin.listings().expiredOf(p.getUniqueId()).size();
        inv.setItem(S_ACTIVE, button(Material.ENDER_CHEST, "&6Twoje aktywne przedmioty",
                List.of("&7» &fAktywnych ofert: &a" + myActive, "&7» &aKliknij lewym, aby przejść!")));
        inv.setItem(S_EXPIRED, button(Material.CHEST, "&6Przedmioty, które wygasły",
                List.of("&7» &fPosiadasz &a" + myExpired + " &fwygaśniętych przedmiotów!", "&7» &aKliknij lewym, aby przejść!")));
        List<String> sl = new ArrayList<>();
        sl.add("&7» &aKliknij lewym, aby przejść!");
        if (st.search != null) {
            sl.add("");
            sl.add("&7Aktualna fraza: &e" + st.search);
            sl.add("&7» &cKliknij prawym, aby wyczyścić!");
        }
        inv.setItem(S_SEARCH, button(Material.OAK_SIGN, "&6Wyszukaj produkt", sl));
        inv.setItem(S_CLOSE, button(Material.BARRIER, "&cZamknij!", List.of()));

        List<String> so = new ArrayList<>();
        so.add("&7» &fWyświetlaj według:");
        for (Sort s : Sort.values()) so.add(s == st.sort ? "&e▶ &e&n" + s.label : "&f▶ " + s.label);
        so.add("");
        so.add("&7» &aKliknij &2PRAWYM&a, aby przełączyć!");
        inv.setItem(S_SORT, button(Material.REPEATER, "&6Sortowanie", so));

        // strzałki stron: zawsze widoczne, działają tylko gdy jest dokąd przejść
        boolean hasPrev = st.page > 0, hasNext = st.page < pages - 1;
        ItemStack prev = button(Material.RED_DYE, "&cPoprzednia strona",
                List.of(hasPrev ? "&7Przejdź na stronę &f" + st.page + "&7/&f" + pages : "&8Brak poprzedniej strony"));
        prev.setAmount(Math.max(1, Math.min(64, hasPrev ? st.page : 1)));
        inv.setItem(S_PREV, prev);
        ItemStack next = button(Material.LIME_DYE, "&aNastępna strona",
                List.of(hasNext ? "&7Przejdź na stronę &f" + (st.page + 2) + "&7/&f" + pages : "&8Brak następnej strony"));
        next.setAmount(Math.max(1, Math.min(64, hasNext ? st.page + 2 : 1)));
        inv.setItem(S_NEXT, next);

        // jak wystawić przedmiot
        double fee = plugin.getConfig().getDouble("fee-percent", 3.0);
        double hours = plugin.getConfig().getDouble("listing-duration-hours", 24);
        String feeS = fee == Math.floor(fee) ? String.valueOf((long) fee) : String.valueOf(fee);
        String hoursS = hours == Math.floor(hours) ? String.valueOf((long) hours) : String.valueOf(hours);
        inv.setItem(S_HELP, button(Material.BOOK, "&aJak wystawić przedmiot?!", List.of(
                "&7» Weź przedmiot do ręki i wpisz",
                "&7   komendę &f/ah sell <cena>",
                "",
                "&7» Prowizja od sprzedaży: &c" + feeS + "%",
                "&7» Oferta wisi na rynku: &e" + hoursS + "h",
                "",
                "&7» Przykładowe ceny: &f1000&7, &f2.5k&7, &f1m")));
    }

    private void renderPersonal(Player p, Holder h) {
        Inventory inv = h.inv;
        h.slots.clear();
        for (int i = 0; i < 45; i++) inv.setItem(i, null);
        ItemStack filler = filler();
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        inv.setItem(S_CLOSE, button(Material.BARRIER, "&cZamknij!", List.of("&7Powrót do rynku")));

        boolean act = h.mode == Mode.ACTIVE;
        List<Listing> list = act ? plugin.listings().activeOf(p.getUniqueId()) : plugin.listings().expiredOf(p.getUniqueId());
        long now = System.currentTimeMillis();
        for (int i = 0; i < Math.min(45, list.size()); i++) {
            Listing l = list.get(i);
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("&7Cena: &a" + Text.money(l.price));
            if (act) {
                extra.add("&7Wygasa za: &e" + Text.duration(l.expires - now));
                extra.add("");
                extra.add("&eKliknij, aby wycofać ofertę!");
            } else {
                extra.add("&cOferta wygasła.");
                extra.add("");
                extra.add("&aKliknij, aby odebrać przedmiot!");
            }
            inv.setItem(i, display(l, extra));
            h.slots.put(i, l.id);
        }
    }

    private List<Listing> filter(State st, List<Listing> active) {
        Category cat = st.category == null ? null : plugin.category(st.category);
        SmartSearch.Query q = st.search == null ? null : SmartSearch.parse(st.search);
        Map<Listing, Double> scores = new HashMap<>();
        List<Listing> out = new ArrayList<>();
        for (Listing l : active) {
            if (cat != null && !cat.matches(l, st.sub)) continue;
            if (q != null) {
                double s = SmartSearch.score(l, q);
                if (s < 0) continue;
                scores.put(l, s);
            }
            out.add(l);
        }
        Comparator<Listing> cmp;
        switch (st.sort) {
            case PRICE_DESC -> cmp = Comparator.comparingDouble((Listing l) -> l.price).reversed();
            case PRICE_ASC -> cmp = Comparator.comparingDouble((Listing l) -> l.price);
            case NEWEST -> cmp = Comparator.comparingLong((Listing l) -> l.created).reversed();
            case OLDEST -> cmp = Comparator.comparingLong((Listing l) -> l.created);
            default -> {
                if (q != null) cmp = Comparator.comparingDouble((Listing l) -> scores.getOrDefault(l, 0.0)).reversed();
                else cmp = null;
            }
        }
        if (cmp != null) out.sort(cmp);
        return out;
    }

    private ItemStack categoryButton(Category c, State st, List<Listing> active) {
        boolean on = c.id.equals(st.category);
        int sel = on ? Math.min(st.sub, c.subs.size() - 1) : 0;
        int count = 0;
        for (Listing l : active) if (c.matches(l, sel)) count++;

        List<String> lore = new ArrayList<>();
        lore.add("&7» Produktów w tej kategorii: &f" + count);
        lore.add("");
        lore.add("&7» " + c.color + "Dostępne kategorie:");
        for (int i = 0; i < c.subs.size(); i++) {
            String n = c.subs.get(i).name;
            lore.add(on && i == sel ? "  &f▶ " + c.color + "&n" + n : "  &f▶ " + n);
        }
        lore.add("");
        if (on) {
            lore.add("&7» &aKliknij lewym, aby przełączyć filtr!");
            lore.add("&7» &cKliknij prawym, aby wyłączyć filtr!");
        } else {
            lore.add("&7» &aKliknij, aby wybrać ten filtr!");
        }
        ItemStack it = button(c.icon, "&fFiltruj według " + c.color + c.title, lore);
        if (on) {
            ItemMeta m = it.getItemMeta();
            m.setEnchantmentGlintOverride(true);
            it.setItemMeta(m);
        }
        return it;
    }

    // ===================== KLIKNIĘCIA =====================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        ClickType ct = e.getClick();

        switch (h.mode) {
            case MARKET -> clickMarket(p, h, slot, ct);
            case ACTIVE, EXPIRED -> {
                if (slot == S_CLOSE) { later(() -> openMarket(p, false)); return; }
                UUID id = h.slots.get(slot);
                if (id != null) {
                    plugin.market().take(p, id);
                    renderPersonal(p, h);
                }
            }
            case CONFIRM -> {
                if (slot == 11) {
                    plugin.market().buy(p, h.target);
                    later(() -> openMarket(p, false));
                } else if (slot == 15) {
                    later(() -> openMarket(p, false));
                }
            }
        }
    }

    private void clickMarket(Player p, Holder h, int slot, ClickType ct) {
        State st = state(p);
        List<Category> cats = plugin.categories();

        for (int i = 0; i < CAT_SLOTS.length; i++) {
            if (slot != CAT_SLOTS[i] || i >= cats.size()) continue;
            Category c = cats.get(i);
            boolean on = c.id.equals(st.category);
            if (ct.isRightClick()) {
                if (on) st.category = null;
            } else if (on) {
                st.sub = (st.sub + 1) % c.subs.size();
            } else {
                st.category = c.id;
                st.sub = 0;
            }
            st.page = 0;
            render(p, h);
            return;
        }

        UUID id = h.slots.get(slot);
        if (id != null) {
            Listing l = plugin.listings().get(id);
            if (l == null || l.isExpired()) { plugin.send(p, "gone"); render(p, h); return; }
            if (l.seller.equals(p.getUniqueId())) { plugin.send(p, "own-listing"); return; }
            if (ct.isLeftClick()) openConfirm(p, l);
            return;
        }

        switch (slot) {
            case S_ACTIVE -> openPersonal(p, Mode.ACTIVE);
            case S_EXPIRED -> openPersonal(p, Mode.EXPIRED);
            case S_SEARCH -> {
                if (ct.isRightClick() && st.search != null) { st.search = null; st.page = 0; render(p, h); }
                else startSearch(p);
            }
            case S_CLOSE -> p.closeInventory();
            case S_SORT -> { st.sort = st.sort.next(); st.page = 0; render(p, h); }
            case S_PREV -> { if (st.page > 0) { st.page--; render(p, h); } }
            case S_NEXT -> { if (st.page < h.pages - 1) { st.page++; render(p, h); } }
            default -> {}
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
        int top = e.getView().getTopInventory().getSize();
        for (int s : e.getRawSlots()) if (s < top) { e.setCancelled(true); return; }
    }

    // ===================== WYSZUKIWANIE (czat) =====================

    private void startSearch(Player p) {
        long timeout = plugin.getConfig().getLong("search-timeout-seconds", 60) * 1000L;
        awaitingSearch.put(p.getUniqueId(), System.currentTimeMillis() + timeout);
        p.closeInventory();
        p.showTitle(Title.title(Text.c(plugin.raw("search-title")), Text.c(plugin.raw("search-subtitle")),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(400))));
        plugin.send(p, "search-prompt");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Long until = awaitingSearch.get(p.getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() > until) { awaitingSearch.remove(p.getUniqueId()); return; }
        e.setCancelled(true);
        awaitingSearch.remove(p.getUniqueId());
        String text = Text.plain(e.message()).trim();
        later(() -> {
            if (!p.isOnline()) return;
            if (text.isEmpty() || text.equalsIgnoreCase("anuluj")) {
                plugin.send(p, "search-cancel");
            } else {
                State st = state(p);
                st.search = text.length() > 60 ? text.substring(0, 60) : text;
                st.page = 0;
                plugin.send(p, "search-set", "{query}", st.search);
            }
            openMarket(p, false);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
        awaitingSearch.remove(e.getPlayer().getUniqueId());
    }

    // ===================== NARZĘDZIA =====================

    private void later(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    private ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        m.setHideTooltip(true);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack button(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(Text.c(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Text.c(s));
        m.lore(l);
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack display(Listing l, List<String> extra) {
        ItemStack it = l.item.clone();
        ItemMeta m = it.getItemMeta();
        List<Component> lore = m.hasLore() && m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<>();
        for (String s : extra) lore.add(Text.c(s));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }
}
