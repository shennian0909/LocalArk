package org.springframework.http;
public enum HttpStatus {
    OK(200), PARTIAL_CONTENT(206), NOT_MODIFIED(304), BAD_REQUEST(400),
    UNAUTHORIZED(401), FORBIDDEN(403), NOT_FOUND(404), METHOD_NOT_ALLOWED(405),
    REQUESTED_RANGE_NOT_SATISFIABLE(416), INTERNAL_SERVER_ERROR(500), BAD_GATEWAY(502);
    private final int code;
    HttpStatus(int code) { this.code = code; }
    public int value() { return code; }
}
