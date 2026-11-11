package kz.tiyn.task;

import java.util.UUID;
import kz.tiyn.platform.export.ExportWriter;
import kz.tiyn.platform.security.Role;

public interface TaskDataReader {

    void writeTo(UUID userId, Role role, ExportWriter out);
}
