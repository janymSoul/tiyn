package kz.tiyn.media;

import java.io.InputStream;
import java.net.URI;

public interface Storage {

    String put(String scope, String contentType, InputStream body);

    URI presignView(String objectKey);
}
