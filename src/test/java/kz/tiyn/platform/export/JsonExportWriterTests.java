package kz.tiyn.platform.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JsonExportWriterTests {

    private record Entry(String name, Instant at) {}

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonNode written(Writing writing) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (JsonGenerator generator = mapper.getFactory().createGenerator(bytes)) {
            generator.writeStartObject();
            writing.write(new JsonExportWriter(generator));
            generator.writeEndObject();
        }
        return mapper.readTree(bytes.toByteArray());
    }

    @FunctionalInterface
    private interface Writing {
        void write(JsonExportWriter out);
    }

    @Test
    void fieldsAreWrittenInCallOrderWithRecordsAsObjects() throws IOException {
        Instant at = Instant.parse("2026-11-01T10:15:30Z");

        JsonNode json = written(out -> {
            out.field("first", "a");
            out.field("entry", new Entry("n", at));
            out.field("missing", null);
        });

        assertThat(json.fieldNames()).toIterable().containsExactly("first", "entry", "missing");
        assertThat(json.at("/entry/at").asText()).isEqualTo("2026-11-01T10:15:30Z");
        assertThat(json.get("missing").isNull()).isTrue();
    }

    @Test
    void anArrayStreamsItsItemsInOrderAndAnEmptyOneIsEmpty() throws IOException {
        JsonNode json = written(out -> {
            out.array("numbers", items -> {
                items.add(1);
                items.add(2);
                items.add(3);
            });
            out.array("none", items -> {});
        });

        assertThat(json.get("numbers")).hasSize(3);
        assertThat(json.at("/numbers/2").asInt()).isEqualTo(3);
        assertThat(json.get("none").isArray()).isTrue();
        assertThat(json.get("none")).isEmpty();
    }

    @Test
    void aBrokenTargetSurfacesAsAnUncheckedIoException() throws IOException {
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("client went away");
            }
        };
        try (JsonGenerator generator = mapper.getFactory().createGenerator(broken)) {
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT);
            generator.writeStartObject();
            var out = new JsonExportWriter(generator);

            assertThatThrownBy(() -> out.field("big", "x".repeat(70_000))).isInstanceOf(UncheckedIOException.class);
        } catch (IOException expectedOnClose) {
            assertThat(expectedOnClose).hasMessageContaining("client went away");
        }
    }
}
