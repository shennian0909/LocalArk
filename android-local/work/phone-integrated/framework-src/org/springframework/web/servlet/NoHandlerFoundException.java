package org.springframework.web.servlet;
/** Metadata only; route-miss handling is explicitly dispatched by the host. */
public class NoHandlerFoundException extends javax.servlet.ServletException {
    private final String httpMethod;
    private final String requestURL;
    public NoHandlerFoundException(String httpMethod, String requestURL, Object unusedHeaders) {
        super("No handler for " + httpMethod + " " + requestURL);
        this.httpMethod = httpMethod;
        this.requestURL = requestURL;
    }
    public String getHttpMethod() { return httpMethod; }
    public String getRequestURL() { return requestURL; }
}
