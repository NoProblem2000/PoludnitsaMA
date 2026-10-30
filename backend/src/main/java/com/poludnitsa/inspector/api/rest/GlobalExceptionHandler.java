package com.poludnitsa.inspector.api.rest;

import com.poludnitsa.inspector.api.dto.ApiErrorDto;
import com.poludnitsa.inspector.common.InspectorException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps every failure to {@link ApiErrorDto}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InspectorException.class)
    public ResponseEntity<ApiErrorDto> inspector(InspectorException e, HttpServletRequest request) {
        if (e.status() >= 500) {
            log.warn("{} {} failed: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        }
        return error(e.status(), e.code(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorDto> invalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError field
                        ? field.getField() + " " + field.getDefaultMessage()
                        : error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(400, "VALIDATION_FAILED", message, request);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorDto> badRequest(Exception e, HttpServletRequest request) {
        return error(400, "VALIDATION_FAILED", e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorDto> unexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse response) {
            // Spring MVC's own errors (404 no handler, 405, 415, ...) keep their status.
            int status = response.getStatusCode().value();
            return error(status, "HTTP_" + status, e.getMessage(), request);
        }
        log.error("{} {} failed", request.getMethod(), request.getRequestURI(), e);
        return error(500, "INTERNAL_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(), request);
    }

    private static ResponseEntity<ApiErrorDto> error(int status, String code, String message,
                                                     HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiErrorDto(Instant.now(), status, code, message, request.getRequestURI()));
    }
}
