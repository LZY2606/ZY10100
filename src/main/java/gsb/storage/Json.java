package gsb.storage;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.io.IOException;
import java.time.Instant;

/** Shared Jackson configuration with self-contained Instant support. */
public final class Json {
    private Json() {}

    public static final ObjectMapper MAPPER = build();

    private static ObjectMapper build() {
        ObjectMapper m = new ObjectMapper();
        SimpleModule time = new SimpleModule();
        time.addSerializer(Instant.class, new ToStringSerializer(Instant.class));
        time.addDeserializer(Instant.class, new StdDeserializer<>(Instant.class) {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return Instant.parse(p.getValueAsString());
            }
        });
        m.registerModule(time);
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        m.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        m.setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY);
        return m;
    }

    public static byte[] write(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON write failed", e);
        }
    }

    public static String writeString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON write failed", e);
        }
    }

    public static <T> T read(byte[] data, Class<T> type) {
        try {
            return MAPPER.readValue(data, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON read failed", e);
        }
    }
}
