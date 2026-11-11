package kz.tiyn.privacy.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import kz.tiyn.identity.AccountDataReader;
import kz.tiyn.ledger.WalletDataReader;
import kz.tiyn.platform.export.ExportProperties;
import kz.tiyn.platform.export.JsonExportWriter;
import kz.tiyn.platform.idempotency.OncePerWindow;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.web.ApiException;
import kz.tiyn.task.TaskDataReader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class ExportService {

    static final String OPERATION = "privacy.export";
    static final String CONSISTENCY =
            "Each section is read in its own read-only snapshot; sections are not one global snapshot.";

    private final AccountDataReader accounts;
    private final TaskDataReader tasks;
    private final WalletDataReader wallets;
    private final OncePerWindow window;
    private final ExportProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    ExportService(
            AccountDataReader accounts,
            TaskDataReader tasks,
            WalletDataReader wallets,
            OncePerWindow window,
            ExportProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.accounts = accounts;
        this.tasks = tasks;
        this.wallets = wallets;
        this.window = window;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    Instant admit(UUID userId) {
        if (!window.tryAcquire(userId, OPERATION, properties.minInterval())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "export_rate_limited");
        }
        return clock.instant();
    }

    void write(Principal caller, Instant generatedAt, OutputStream target) {
        try (JsonGenerator generator = json.getFactory().createGenerator(target)) {
            var out = new JsonExportWriter(generator);
            generator.writeStartObject();
            out.field("generatedAt", generatedAt);
            out.field("consistency", CONSISTENCY);
            accounts.writeTo(caller.userId(), caller.role(), out);
            tasks.writeTo(caller.userId(), caller.role(), out);
            wallets.writeTo(caller.userId(), caller.role(), out);
            generator.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
