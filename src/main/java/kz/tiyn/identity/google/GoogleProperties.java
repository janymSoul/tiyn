package kz.tiyn.identity.google;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tiyn.google")
public record GoogleProperties(@NotEmpty List<String> clientIds) {

    public GoogleProperties {
        clientIds = clientIds == null ? null : List.copyOf(clientIds);
    }
}
