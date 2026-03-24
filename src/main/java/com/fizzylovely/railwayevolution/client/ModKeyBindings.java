package com.fizzylovely.railwayevolution.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the F10 key binding for the Train Control Panel.
 * Call {@link #register(RegisterKeyMappingsEvent)} from the mod event bus on Dist.CLIENT.
 */
@SuppressWarnings("null")
public class ModKeyBindings {

    public static final KeyMapping OPEN_PANEL = new KeyMapping(
            "key.create_railway.open_panel",     // translation key
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F10,
            "key.categories.create_railway"      // category key
    );

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PANEL);
    }
}
