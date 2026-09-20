package gsb.web;

import gsb.store.ApiException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal multipart/form-data parser (files + text fields), no dependencies. */
public final class Multipart {
    public static final class Part {
        public final String name;
        public final String filename;
        public final String contentType;
        public final byte[] data;

        Part(String name, String filename, String contentType, byte[] data) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
            this.data = data;
        }

        public String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private final Map<String, List<Part>> parts = new LinkedHashMap<>();

    private Multipart() {}

    public static Multipart parse(byte[] body, String contentType) {
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            throw ApiException.badRequest("BAD_CONTENT_TYPE",
                    "expected multipart/form-data upload");
        }
        int idx = contentType.indexOf("boundary=");
        if (idx < 0) {
            throw ApiException.badRequest("BAD_BOUNDARY", "missing multipart boundary");
        }
        String boundary = contentType.substring(idx + 9).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        Multipart mp = new Multipart();
        int pos = indexOf(body, delim, 0);
        while (pos >= 0) {
            int headerStart = pos + delim.length;
            if (headerStart + 2 <= body.length && body[headerStart] == '-'
                    && body[headerStart + 1] == '-') {
                break;
            }
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), headerStart);
            if (headerEnd < 0) {
                break;
            }
            String headers = new String(body, headerStart, headerEnd - headerStart,
                    StandardCharsets.UTF_8);
            int dataStart = headerEnd + 4;
            int next = indexOf(body, delim, dataStart);
            if (next < 0) {
                break;
            }
            int dataEnd = next - 2;
            byte[] data = slice(body, dataStart, dataEnd);
            mp.add(parseHeaders(headers), data);
            pos = next;
        }
        return mp;
    }

    public Part file(String name) {
        List<Part> list = parts.get(name);
        if (list == null) {
            return null;
        }
        for (Part p : list) {
            if (p.filename != null) {
                return p;
            }
        }
        return null;
    }

    public String field(String name) {
        List<Part> list = parts.get(name);
        return list == null || list.isEmpty() ? null : list.get(0).text();
    }

    private void add(Headers headers, byte[] data) {
        Part part = new Part(headers.name, headers.filename, headers.contentType, data);
        parts.computeIfAbsent(headers.name, k -> new ArrayList<>()).add(part);
    }

    private static final class Headers {
        String name;
        String filename;
        String contentType;
    }

    private static Headers parseHeaders(String block) {
        Headers h = new Headers();
        String disposition = null;
        for (String rawLine : block.split("\r\n")) {
            String line = rawLine.trim();
            if (line.toLowerCase().startsWith("content-disposition:")) {
                disposition = line;
            } else if (line.toLowerCase().startsWith("content-type:")) {
                h.contentType = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        if (disposition != null) {
            h.name = attr(disposition, "name");
            h.filename = attr(disposition, "filename");
        }
        if (h.name == null) {
            throw ApiException.badRequest("BAD_PART", "form part missing name");
        }
        return h;
    }

    private static String attr(String header, String key) {
        int i = header.indexOf(key + "=\"");
        if (i < 0) {
            return null;
        }
        int start = header.indexOf('"', i) + 1;
        int end = header.indexOf('"', start);
        return end < 0 ? null : header.substring(start, end);
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] slice(byte[] data, int from, int to) {
        byte[] out = new byte[Math.max(0, to - from)];
        System.arraycopy(data, from, out, 0, out.length);
        return out;
    }
}
