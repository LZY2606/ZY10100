package gsb;

import gsb.web.Multipart;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultipartTest {
    @Test
    void parsesFieldsAndFile() {
        String b = "----xyz";
        String body =
            "--" + b + "\r\n" +
            "Content-Disposition: form-data; name=\"meta\"\r\n\r\n" +
            "{\"name\":\"x\"}\r\n" +
            "--" + b + "\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n" +
            "hello" + "\r\n" +
            "--" + b + "--\r\n";
        Multipart mp = Multipart.parse(body.getBytes(),
            "multipart/form-data; boundary=" + b);
        assertEquals("{\"name\":\"x\"}", mp.field("meta"));
        assertNotNull(mp.file("file"));
        assertEquals("a.bin", mp.file("file").filename);
        assertEquals("hello", new String(mp.file("file").data));
    }
}
