package kz.tiyn.platform.idempotency;

import kz.tiyn.platform.web.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class IdempotencyContextResolver implements HandlerMethodArgumentResolver {

    static final String HEADER = "Idempotency-Key";
    static final String INVALID_KEY = "invalid_idempotency_key";
    private static final int MAX_KEY_LENGTH = 100;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return IdempotencyContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest request,
            WebDataBinderFactory binderFactory) {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, INVALID_KEY);
        }
        return new IdempotencyContext(key);
    }
}
