package cn.ussshenzhou.notenoughbandwidth.config;

import net.minecraft.client.gui.GuiScreen;

import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.SimpleGuiConfig;

import cn.ussshenzhou.notenoughbandwidth.Tags;

public class NebGuiConfig extends SimpleGuiConfig {

    public NebGuiConfig(GuiScreen parentScreen) throws ConfigException {
        super(parentScreen, Tags.MODID, Tags.MODNAME, true, NebConfig.class);
    }
}
