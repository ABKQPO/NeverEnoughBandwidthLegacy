package cn.ussshenzhou.notenoughbandwidth.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Opens the statistics screen after Minecraft has finished processing input.
 */
public class NebKeyHandler {

    private final KeyBinding statistics = new KeyBinding("key.neb.stat", Keyboard.KEY_N, "key.categories.neb");

    public void register() {
        ClientRegistry.registerKeyBinding(statistics);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft client = Minecraft.getMinecraft();
        while (statistics.isPressed()) {
            // Vanilla 1.7.10 has no modifier-aware key binding; keep Alt for the default N key.
            boolean modifier = statistics.getKeyCode() != Keyboard.KEY_N || Keyboard.isKeyDown(Keyboard.KEY_LMENU)
                || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
            if (modifier && client.theWorld != null && client.currentScreen == null) {
                client.displayGuiScreen(new NebStatScreen());
            }
        }
    }
}
