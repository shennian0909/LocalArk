package javax.servlet.http;
import java.io.IOException;
import javax.servlet.ServletOutputStream;
public interface HttpServletResponse extends javax.servlet.ServletResponse {
    int SC_OK = 200;
    void setHeader(String name, String value);
    void setStatus(int status);
    void sendError(int status) throws IOException;
    void setContentType(String contentType);
    void setContentLengthLong(long length);
    boolean isCommitted();
    ServletOutputStream getOutputStream() throws IOException;
}
