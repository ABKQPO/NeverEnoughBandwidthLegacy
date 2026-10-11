package cn.ussshenzhou.notenoughbandwidth.config;

import net.minecraft.client.gui.GuiScreen;

import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.SimpleGuiConfig;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;

/**
 * In-game configuration screen, reached from the mod list.
 *
 * @author USS_Shenzhou
 */
public class NebGuiConfig extends SimpleGuiConfig {

    public NebGuiConfig(GuiScreen parentScreen) throws ConfigException {
        super(parentScreen, ModConstants.MOD_ID, ModConstants.MOD_NAME, true, NebConfig.class);
    }
}
