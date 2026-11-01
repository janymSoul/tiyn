package kz.tiyn.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class GlobalExceptionHandlerTests {

    @Test
    void anOversizedUploadIsA413WithItsOwnCodeNotA500() {
        var response = new GlobalExceptionHandler().handleUploadTooLarge(new MaxUploadSizeExceededException(1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isEqualTo(new ErrorBody("payload_too_large"));
    }
}
