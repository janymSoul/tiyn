package kz.tiyn.platform.export;

import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class JsonExportWriter implements ExportWriter {

    private final JsonGenerator generator;

    public JsonExportWriter(JsonGenerator generator) {
        this.generator = generator;
    }

    @Override
    public void field(String name, Object value) {
        io(() -> {
            generator.writeFieldName(name);
            generator.writeObject(value);
        });
    }

    @Override
    public void array(String name, ArrayBody body) {
        io(() -> generator.writeArrayFieldStart(name));
        body.write(item -> io(() -> generator.writeObject(item)));
        io(generator::writeEndArray);
    }

    private static void io(IoAction action) {
        try {
            action.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface IoAction {

        void run() throws IOException;
    }
}
