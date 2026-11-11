package kz.tiyn.platform.export;

public interface ExportWriter {

    void field(String name, Object value);

    void array(String name, ArrayBody body);

    @FunctionalInterface
    interface ArrayBody {

        void write(Items items);
    }

    @FunctionalInterface
    interface Items {

        void add(Object item);
    }
}
