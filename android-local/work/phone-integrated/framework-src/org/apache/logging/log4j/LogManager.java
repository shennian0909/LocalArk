package org.apache.logging.log4j;
public final class LogManager {
    private static final Logger SILENT = new Logger() { };
    private LogManager() { }
    public static Logger getLogger() { return SILENT; }
    public static Logger getLogger(Class<?> type) { return SILENT; }
    public static Logger getLogger(String name) { return SILENT; }
}
