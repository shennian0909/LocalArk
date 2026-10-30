package javax.servlet;
public class ServletException extends Exception {
    public ServletException() { }
    public ServletException(String message) { super(message); }
    public ServletException(Throwable cause) { super(cause); }
    public ServletException(String message, Throwable cause) { super(message, cause); }
}
