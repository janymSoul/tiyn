package kz.tiyn.privacy.export;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import kz.tiyn.platform.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ExportController {

    private final ExportService service;

    ExportController(ExportService service) {
        this.service = service;
    }

    @GetMapping("/me/export")
    void export(@AuthenticationPrincipal Principal caller, HttpServletResponse response) throws IOException {
        Instant generatedAt = service.admit(caller.userId());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment(generatedAt));
        service.write(caller, generatedAt, response.getOutputStream());
    }

    private static String attachment(Instant generatedAt) {
        String date = generatedAt.atOffset(ZoneOffset.UTC).toLocalDate().toString();
        return ContentDisposition.attachment()
                .filename("tiyn-export-" + date + ".json")
                .build()
                .toString();
    }
}
