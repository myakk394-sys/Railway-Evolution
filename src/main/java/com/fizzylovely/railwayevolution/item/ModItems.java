package com.fizzylovely.railwayevolution.item;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nonnull;

/**
 * Item registry for Create: Railway Evolution.
 * Register via DeferredRegister on the mod event bus.
 *
 * NOTE (1.19.2): ArmorItem uses EquipmentSlot (not ArmorItem.Type which was added in 1.20).
 *                ArmorMaterial uses getDurabilityForSlot / getDefenseForSlot (not ForType).
 */
@SuppressWarnings("null")
public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CreateRailwayMod.MOD_ID);

    /** Minimal ArmorMaterial for the AI Goggles (helmet-only, no real protection). */
    private static final ArmorMaterial GOGGLES_MATERIAL = new ArmorMaterial() {
        // 1.19.2 API: getDurabilityForSlot(EquipmentSlot)
        @Override public int getDurabilityForSlot(@Nonnull EquipmentSlot slot) { return 150; }
        // 1.19.2 API: getDefenseForSlot(EquipmentSlot)
        @Override public int getDefenseForSlot(@Nonnull EquipmentSlot slot) { return 0; }
        @Override public int getEnchantmentValue() { return 0; }
        @Override public SoundEvent getEquipSound() { return SoundEvents.ARMOR_EQUIP_LEATHER; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.EMPTY; }
        @Override public String getName() { return "create_railway_goggles"; }
        @Override public float getToughness() { return 0; }
        @Override public float getKnockbackResistance() { return 0; }
    };

    /**
     * AI Debug Goggles — when worn in the helmet slot, shows:
     *   - The 50-block forward scan beam (END_ROD particles)
     *   - VBS track occupation (FLAME particles at reserved segment midpoints)
     *   - Action-bar text with state/speed info for all trains within 141 blocks
     *
     * NOTE (1.19.2): Constructor is ArmorItem(material, EquipmentSlot, Properties)
     *                (ArmorItem.Type.HELMET was added in 1.20)
     */
    public static final RegistryObject<Item> AI_GOGGLES =
            ITEMS.register("ai_goggles",
                    () -> new ArmorItem(GOGGLES_MATERIAL, EquipmentSlot.HEAD,
                            new Item.Properties().stacksTo(1)));
}
