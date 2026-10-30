package javax.servlet;
import java.io.IOException;
public interface FilterChain {
    void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException;
}
