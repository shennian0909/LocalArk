package org.slf4j;
public final class LoggerFactory {
    private static final Logger SILENT = new Logger() { };
    private LoggerFactory() { }
    public static Logger getLogger() { return SILENT; }
    public static Logger getLogger(Class<?> type) { return SILENT; }
    public static Logger getLogger(String name) { return SILENT; }
}
