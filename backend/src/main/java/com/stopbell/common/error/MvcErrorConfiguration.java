package com.stopbell.common.error;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MvcErrorConfiguration implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(MvcErrorConfiguration.class);
    private final ObjectMapper objectMapper;

    public MvcErrorConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        // Run AFTER local/advice handlers, ResponseStatusExceptionResolver and MVC's default resolver.
        resolvers.add((request, response, handler, exception) -> {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof AuthenticationException || cause instanceof AccessDeniedException) {
                    return null; // Leave Security exceptions to the surrounding filter chain.
                }
            }
            if (response.isCommitted()) {
                return null;
            }
            // Exception messages and causes may contain credentials, SQL, or provider responses.
            log.error("Unhandled MVC failure: exceptionType={}", exception.getClass().getSimpleName());
            response.resetBuffer();
            response.setHeader(HttpHeaders.CONTENT_LENGTH, null);
            response.setStatus(500);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            try {
                objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.forStatus(500));
                return new ModelAndView();
            } catch (IOException writeFailure) {
                return null; // A disconnected client cannot receive a replacement response.
            }
        });
    }
}
