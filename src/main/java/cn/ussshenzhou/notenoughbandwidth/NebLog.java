package cn.ussshenzhou.notenoughbandwidth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class NebLog {

    private static final Logger LOGGER = LogManager.getLogger(Tags.MODNAME);

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
}
