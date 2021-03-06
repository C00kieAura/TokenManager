/*
 *
 *   This file is part of TokenManager, licensed under the MIT License.
 *
 *   Copyright (c) Realized
 *   Copyright (c) contributors
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all
 *   copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE.
 *
 */

package me.realized.tokenmanager.shop;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.Getter;
import me.realized.tokenmanager.TokenManagerPlugin;
import me.realized.tokenmanager.config.Config;
import me.realized.tokenmanager.util.Log;
import me.realized.tokenmanager.util.NumberUtil;
import me.realized.tokenmanager.util.Reloadable;
import me.realized.tokenmanager.util.StringUtil;
import me.realized.tokenmanager.util.compat.Items;
import me.realized.tokenmanager.util.config.AbstractConfiguration;
import me.realized.tokenmanager.util.inventory.GUIBuilder;
import me.realized.tokenmanager.util.inventory.GUIBuilder.Pattern;
import me.realized.tokenmanager.util.inventory.ItemBuilder;
import me.realized.tokenmanager.util.inventory.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class ShopsConfig extends AbstractConfiguration<TokenManagerPlugin> implements Reloadable {

    private final Map<String, Shop> shops = new LinkedHashMap<>();

    @Getter
    private final Map<UUID, ConfirmInventory> inventories = new HashMap<>();

    @Getter
    private Inventory confirmGuiSample;

    public ShopsConfig(final TokenManagerPlugin plugin) {
        super(plugin, "shops");
    }

    @Override
    protected void loadValues(final FileConfiguration configuration) {
        final ConfigurationSection section = configuration.getConfigurationSection("shops");

        if (section == null) {
            return;
        }

        for (final String name : section.getKeys(false)) {
            final ConfigurationSection shopSection = section.getConfigurationSection(name);
            final Shop shop;

            try {
                shop = new Shop(
                    name,
                    shopSection.getString("title", "&cShop title was not specified."),
                    shopSection.getInt("rows", 1),
                    shopSection.getBoolean("auto-close", false),
                    shopSection.getBoolean("use-permission", false),
                    shopSection.getBoolean("confirm-purchase", false)
                );
            } catch (IllegalArgumentException ex) {
                Log.error(this, "Failed to initialize shop '" + name + "': " + ex.getMessage());
                continue;
            }

            final ConfigurationSection itemsSection = shopSection.getConfigurationSection("items");

            if (itemsSection != null) {
                for (final String num : itemsSection.getKeys(false)) {
                    final OptionalLong target = NumberUtil.parseLong(num);

                    if (!target.isPresent()) {
                        Log.error(this, "Failed to load slot '" + num + "' of shop '" + name + "': '" + num + "' is not a valid number.");
                        continue;
                    }

                    final long slot = target.getAsLong();

                    if (slot < 0 || slot >= shop.getGui().getSize()) {
                        Log.error(this, "Failed to load slot '" + num + "' of shop '" + name + "': '" + slot + "' is over the shop size.");
                        continue;
                    }

                    final ConfigurationSection slotSection = itemsSection.getConfigurationSection(num);
                    final ItemStack displayed;

                    try {
                        displayed = ItemUtil.loadFromString(slotSection.getString("displayed"));
                    } catch (Exception ex) {
                        shop.getGui().setItem((int) slot, ItemBuilder
                            .of(Material.REDSTONE_BLOCK)
                            .name("&4&m------------------")
                            .lore(
                                "&cThere was an error",
                                "&cwhile loading this",
                                "&citem, please contact",
                                "&can administrator.",
                                "&4&m------------------"
                            )
                            .build()
                        );
                        Log.error(this, "Failed to load displayed item for slot '" + num + "' of shop '" + name + "': " + ex.getMessage());
                        continue;
                    }

                    shop.setSlot((int) slot, displayed, new Slot(
                        (int) slot,
                        slotSection.getInt("cost", 1000000),
                        displayed,
                        slotSection.getString("message"),
                        slotSection.getString("subshop"),
                        slotSection.getStringList("commands"),
                        slotSection.getBoolean("use-permission", false),
                        slotSection.getBoolean("confirm-purchase", false)
                    ));
                }
            }

            register(name, shop);
        }

        final Config config = plugin.getConfiguration();

        this.confirmGuiSample = GUIBuilder.of(StringUtil.color(config.getConfirmPurchaseTitle()), 3)
            .pattern(
                Pattern.of("AAABBBCCC", "AAABBBCCC", "AAABBBCCC")
                    .specify('A', Items.GREEN_PANE.clone())
                    .specify('B', Items.GRAY_PANE.clone())
                    .specify('C', Items.RED_PANE.clone()))
            .set(
                ConfirmInventory.CONFIRM_PURCHASE_SLOT,
                ItemUtil.loadFromString(config.getConfirmPurchaseConfirm(), error -> Log.error(this, "Failed to load confirm-button: " + error))
            )
            .set(
                ConfirmInventory.CANCEL_PURCHASE_SLOT,
                ItemUtil.loadFromString(config.getConfirmPurchaseCancel(), error -> Log.error(this, "Failed to load cancel-button: " + error))
            )
            .build();
    }

    @Override
    public void handleUnload() {
        Bukkit.getOnlinePlayers().forEach(player -> {
            final Inventory top = player.getOpenInventory().getTopInventory();

            if (getShops().stream().anyMatch(shop -> shop.getGui().equals(top))) {
                player.closeInventory();
                player.sendMessage(StringUtil.color("&cShop was automatically closed since the plugin is deactivating."));
                return;
            }

            final ConfirmInventory inventory = inventories.get(player.getUniqueId());

            if (inventory != null && inventory.getInventory().equals(top)) {
                player.closeInventory();
            }
        });

        shops.clear();
    }

    public Optional<Shop> getShop(final String name) {
        return Optional.ofNullable(shops.get(name));
    }

    public Collection<Shop> getShops() {
        return shops.values();
    }

    public Shop register(final String name, final Shop shop) {
        return shops.put(name, shop);
    }
}
