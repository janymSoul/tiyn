package kz.tiyn.identity;

import java.util.UUID;
import kz.tiyn.platform.export.ExportWriter;
import kz.tiyn.platform.security.Role;

public interface AccountDataReader {

    void writeTo(UUID userId, Role role, ExportWriter out);
}
