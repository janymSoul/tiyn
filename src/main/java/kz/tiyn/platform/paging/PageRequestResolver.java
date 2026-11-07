package kz.tiyn.platform.paging;

import kz.tiyn.platform.web.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class PageRequestResolver implements HandlerMethodArgumentResolver {

    static final String INVALID_REQUEST = "invalid_request";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageRequest.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest request,
            WebDataBinderFactory binderFactory) {
        String cursor = request.getParameter("cursor");
        return new PageRequest(
                limit(request.getParameter("limit")),
                cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor));
    }

    private static int limit(String raw) {
        if (raw == null) {
            return PageRequest.DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(raw);
            if (limit < 1 || limit > PageRequest.MAX_LIMIT) {
                throw invalidRequest();
            }
            return limit;
        } catch (NumberFormatException e) {
            throw invalidRequest();
        }
    }

    private static ApiException invalidRequest() {
        return new ApiException(HttpStatus.BAD_REQUEST, INVALID_REQUEST);
    }
}
