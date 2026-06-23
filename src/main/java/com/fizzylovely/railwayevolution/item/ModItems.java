package com.fizzylovely.railwayevolution.item;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Item registry for Create: Railway Evolution.
 * Register via DeferredRegister on the mod event bus.
 *
 * NeoForge 1.21.1: ArmorMaterial is now a Record registered via DeferredRegister.
 */
public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, CreateRailwayMod.MOD_ID);

    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(BuiltInRegistries.ARMOR_MATERIAL, CreateRailwayMod.MOD_ID);

    /** Minimal ArmorMaterial for the AI Goggles (helmet-only, no real protection). */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> GOGGLES_MATERIAL =
            ARMOR_MATERIALS.register("goggles_material", () -> {
                Map<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
                for (ArmorItem.Type type : ArmorItem.Type.values()) {
                    defense.put(type, 0);
                }
                return new ArmorMaterial(
                        defense,
                        0,               // enchantmentValue
                        net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER,
                        () -> net.minecraft.world.item.crafting.Ingredient.EMPTY,
                        List.of(),       // layers (empty = no texture)
                        0.0f,            // toughness
                        0.0f             // knockbackResistance
                );
            });

    /**
     * AI Debug Goggles — when worn in the helmet slot, shows:
     *   - The 50-block forward scan beam (END_ROD particles)
     *   - VBS track occupation (FLAME particles at reserved segment midpoints)
     *   - Action-bar text with state/speed info for all trains within 141 blocks
     */
    public static final DeferredHolder<Item, Item> AI_GOGGLES =
            ITEMS.register("ai_goggles",
                    () -> new ArmorItem(GOGGLES_MATERIAL, ArmorItem.Type.HELMET,
                            new Item.Properties().stacksTo(1)));
}
