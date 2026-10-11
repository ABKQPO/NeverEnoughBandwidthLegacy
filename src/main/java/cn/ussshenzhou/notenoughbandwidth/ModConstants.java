package cn.ussshenzhou.notenoughbandwidth;

/**
 * Compile-time constants shared by the mod and its core mod half.
 * <p>
 * The core mod is loaded long before {@link NotEnoughBandwidth}, so nothing in here may touch
 * Minecraft or Forge classes.
 *
 * @author USS_Shenzhou
 */
public class ModConstants {

    public static final String MOD_ID = "neb";

    public static final String MOD_NAME = "NotEnoughBandwidth";

    /**
     * FML custom payload channel used for the capability handshake and the statistics exchange.
     * FML limits channel names to 20 characters.
     */
    public static final String CHANNEL = "NEB";

    /**
     * Netty pipeline handler name of the outbound aggregator.
     */
    public static final String HANDLER_AGGREGATOR = "neb_aggregator";

    /**
     * Netty pipeline handler name of the inbound expander.
     */
    public static final String HANDLER_EXPANDER = "neb_expander";

    /**
     * Bumped whenever the wire format changes in a way that older peers cannot parse.
     */
    public static final int PROTOCOL_VERSION = 1;

    private ModConstants() {}
}
