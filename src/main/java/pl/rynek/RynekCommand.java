package pl.rynek;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RynekCommand implements CommandExecutor, TabCompleter {
    private final RynekPlugin plugin;

    public RynekCommand(RynekPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("rynek.admin")) { plugin.send(sender, "no-permission"); return true; }
            plugin.reloadSettings();
            plugin.send(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player p)) { plugin.send(sender, "only-player"); return true; }

        switch (sub) {
            case "open", "otworz", "otwórz" -> {
                if (!p.hasPermission("rynek.use")) { plugin.send(p, "no-permission"); return true; }
                plugin.gui().openMarket(p, true);
            }
            case "sell", "sprzedaj" -> {
                if (!p.hasPermission("rynek.sell")) { plugin.send(p, "no-permission"); return true; }
                if (args.length < 2) { plugin.send(p, "sell-usage"); return true; }
                double price = Text.parsePrice(args[1]);
                if (price <= 0) { plugin.send(p, "invalid-price"); return true; }
                plugin.market().sell(p, price);
            }
            default -> plugin.send(p, "usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String in = args[0].toLowerCase(Locale.ROOT);
            if ("sell".startsWith(in)) out.add("sell");
            if (sender.hasPermission("rynek.admin") && "reload".startsWith(in)) out.add("reload");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("sell") || args[0].equalsIgnoreCase("sprzedaj"))) {
            out.add("<cena>");
        }
        return out;
    }
}
