package kz.tiyn.ledger;

import java.util.UUID;
import kz.tiyn.platform.export.ExportWriter;
import kz.tiyn.platform.security.Role;

public interface WalletDataReader {

    void writeTo(UUID userId, Role role, ExportWriter out);
}
