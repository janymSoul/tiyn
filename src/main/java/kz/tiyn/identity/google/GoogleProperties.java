package kz.tiyn.identity.google;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tiyn.google")
public record GoogleProperties(List<String> clientIds) {

    public GoogleProperties {
        clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
    }
}
