package com.servidor.worth;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.*;

/**
 * Mostra o valor de venda no tooltip do item (só no cliente, via pacotes).
 * O item de verdade NUNCA é alterado, então não tem risco de duplicar nem de sujar lore.
 */
public class ItemWorth extends JavaPlugin {

    private Plugin sell;
    private Method priceMethod;
    private final Set<UUID> disabled = new HashSet<>();
    private final NumberFormat fmt = NumberFormat.getNumberInstance(new Locale("pt", "BR"));
    private String linePrice, lineStack, lineUnsellable;
    private boolean showStack, showUnsellable;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        fmt.setMaximumFractionDigits(2);
        fmt.setMinimumFractionDigits(0);
        loadSettings();

        sell = Bukkit.getPluginManager().getPlugin("SellPlugin");
        try {
            priceMethod = sell.getClass().getMethod("getUnitPrice", ItemStack.class);
        } catch (Exception e) {
            getLogger().severe("Atualize o SellPlugin (precisa do método getUnitPrice). Desligando...");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        List<PacketType> types = new ArrayList<>(List.of(
                PacketType.Play.Server.SET_SLOT,
                PacketType.Play.Server.WINDOW_ITEMS));
        try { // só existe em versões mais novas do ProtocolLib/Minecraft
            PacketType cursor = (PacketType) PacketType.Play.Server.class.getField("SET_CURSOR_ITEM").get(null);
            if (cursor.isSupported()) types.add(cursor);
        } catch (Exception ignored) { }

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(this, ListenerPriority.HIGH, types) {
                    @Override
                    public void onPacketSending(PacketEvent e) {
                        handle(e);
                    }
                });
    }

    @Override
    public void onDisable() {
        ProtocolLibrary.getProtocolManager().removePacketListeners(this);
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        linePrice = c.getString("line-price", "&7Vale: &a{price} &7cada");
        lineStack = c.getString("line-stack", "&7Pilha (&f{amount}&7): &a{total}");
        lineUnsellable = c.getString("line-unsellable", "&cNão pode ser vendido");
        showStack = c.getBoolean("show-stack-line", true);
        showUnsellable = c.getBoolean("show-unsellable", true);
    }

    // ---------------- pacotes ----------------

    private void handle(PacketEvent e) {
        Player p = e.getPlayer();
        // No criativo o cliente devolve o item com lore pro servidor, então não mexemos.
        if (p == null || p.getGameMode() == GameMode.CREATIVE) return;
        if (!p.hasPermission("itemworth.see") || disabled.contains(p.getUniqueId())) return;

        try {
            PacketContainer pk = e.getPacket().deepClone();
            if (e.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                List<ItemStack> list = pk.getItemListModifier().read(0);
                List<ItemStack> out = new ArrayList<>(list.size());
                for (ItemStack it : list) out.add(decorate(it));
                pk.getItemListModifier().write(0, out);
                try { // item que está no cursor
                    pk.getItemModifier().write(0, decorate(pk.getItemModifier().read(0)));
                } catch (Exception ignored) { }
            } else {
                pk.getItemModifier().write(0, decorate(pk.getItemModifier().read(0)));
            }
            e.setPacket(pk);
        } catch (Exception ex) {
            // se algo der errado, manda o pacote original sem mexer
        }
    }

    private double unitPrice(ItemStack it) {
        try {
            return (double) priceMethod.invoke(sell, it);
        } catch (Exception ex) {
            return 0;
        }
    }

    private String money(double v) { return "$" + fmt.format(v); }

    private Component line(String template) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(template)
                .decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack decorate(ItemStack it) {
        if (it == null || it.getType().isAir()) return it;

        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;

        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();

        // não mexe no botão verde do /sell
        for (Component c : lore) {
            if (PlainTextComponentSerializer.plainText().serialize(c).contains("Clique para vender")) return it;
        }

        double unit = unitPrice(it);
        int amount = it.getAmount();

        if (unit <= 0) {
            if (!showUnsellable) return it;
            lore.add(line(lineUnsellable));
        } else {
            lore.add(line(linePrice.replace("{price}", money(unit))));
            if (showStack && amount > 1) {
                lore.add(line(lineStack
                        .replace("{amount}", String.valueOf(amount))
                        .replace("{total}", money(unit * amount))));
            }
        }

        meta.lore(lore);
        ItemStack copy = it.clone();
        copy.setItemMeta(meta);
        return copy;
    }

    // ---------------- comando ----------------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!s.hasPermission("itemworth.admin")) {
                s.sendMessage(Component.text("Sem permissão.", NamedTextColor.RED));
                return true;
            }
            loadSettings();
            s.sendMessage(Component.text("Configuração recarregada.", NamedTextColor.GREEN));
            return true;
        }
        if (!(s instanceof Player p)) return true;

        if (disabled.remove(p.getUniqueId())) {
            p.sendMessage(Component.text("Valor dos itens: ligado.", NamedTextColor.GREEN));
        } else {
            disabled.add(p.getUniqueId());
            p.sendMessage(Component.text("Valor dos itens: desligado.", NamedTextColor.RED));
        }
        p.updateInventory(); // reenvia os itens já no novo estado
        return true;
    }
}
