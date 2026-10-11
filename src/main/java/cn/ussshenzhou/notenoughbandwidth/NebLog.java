package cn.ussshenzhou.notenoughbandwidth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Single logger for the whole mod, usable from the core mod phase onwards.
 *
 * @author USS_Shenzhou
 */
public class NebLog {

    private static final Logger LOGGER = LogManager.getLogger(ModConstants.MOD_NAME);

    public static Logger get() {
        return LOGGER;
    }

    public static void info(String message, Object... args) {
        LOGGER.info(message, args);
    }

    public static void warn(String message, Object... args) {
        LOGGER.warn(message, args);
    }

    public static void error(String message, Object... args) {
        LOGGER.error(message, args);
    }

    public static void debug(String message, Object... args) {
        LOGGER.debug(message, args);
    }

    private NebLog() {}
}
