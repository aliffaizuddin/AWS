package dev.cloudlite.s3.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

// Reads parameters from the raw query string only. HttpServletRequest's
// getParameter()/getParameterMap() make Tomcat parse a form-encoded POST body
// into parameters, which leaves controllers an empty body — and curl -d sends
// form encoding by default. Anything on the multipart POST path must use this.
public final class QueryParams {

    private QueryParams() {
    }

    public static boolean has(String queryString, String name) {
        return get(queryString, name) != null;
    }

    // The decoded value, "" for a value-less parameter, or null if absent.
    public static String get(String queryString, String name) {
        if (queryString == null) {
            return null;
        }
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            String rawName = eq < 0 ? pair : pair.substring(0, eq);
            if (decode(rawName).equals(name)) {
                return eq < 0 ? "" : decode(pair.substring(eq + 1));
            }
        }
        return null;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
