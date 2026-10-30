package javax.servlet.http;
public interface HttpServletRequest extends javax.servlet.ServletRequest {
    String getHeader(String name);
    String getParameter(String name);
    String getRemoteAddr();
    String getMethod();
    String getRequestURI();
    String getQueryString();
    String getCharacterEncoding();
}
