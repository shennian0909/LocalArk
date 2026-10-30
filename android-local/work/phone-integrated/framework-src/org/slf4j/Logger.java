package org.slf4j;
/** No-op adapter: never formats arguments or emits request bodies, tokens or exceptions. */
public interface Logger {
    default void trace(String message, Object... args) { }
    default void trace(Object message) { }
    default void debug(String message, Object... args) { }
    default void debug(Object message) { }
    default void info(String message, Object... args) { }
    default void info(Object message) { }
    default void warn(String message, Object... args) { }
    default void warn(Object message) { }
    default void error(String message, Object... args) { }
    default void error(Object message) { }
    default void fatal(String message, Object... args) { }
    default void fatal(Object message) { }
    default boolean isDebugEnabled() { return false; }
    default boolean isTraceEnabled() { return false; }
}
