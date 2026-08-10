package com.example.orders.exception;

import java.util.List;

import com.example.orders.dto.ErrorResponse;
import jakarta.persistence.OptimisticLockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into the single {@link ErrorResponse} contract.
 *
 * <p>Note what is <em>not</em> here: failures raised inside the Spring Security filter chain never
 * reach a {@code @ControllerAdvice}, because the request has not yet entered the DispatcherServlet.
 * Those are handled by {@code RestAuthenticationEntryPoint} and {@code RestAccessDeniedHandler},
 * which write the same shape. Both paths exist deliberately - covering only one leaves a subset of
 * responses in a different format, and clients discover it in production.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Everything the application raises on purpose already knows its code and status. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApiException(ApiException exception) {
        // Expected outcomes: logged at debug, not as errors. A missing order is not an incident,
        // and treating it as one trains everyone to ignore the error log.
        log.debug("Handled {}: {}", exception.errorCode(), exception.getMessage());
        return respond(exception.errorCode(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<ErrorResponse.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors()
                .stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        ErrorResponse body = ErrorResponse.of(ErrorCode.VALIDATION_ERROR,
                "Request validation failed", fieldErrors);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status()).body(body);
    }

    /** Malformed JSON, or a body that cannot be bound at all. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> handleUnreadable(Exception exception) {
        log.debug("Unreadable request: {}", exception.getMessage());
        // The exception message can quote the raw payload and internal type names, so it is not
        // echoed back.
        return respond(ErrorCode.VALIDATION_ERROR, "Malformed request body");
    }

    /**
     * Raised by {@code @PreAuthorize} inside the dispatch. The DispatcherServlet's exception
     * resolvers see it before Spring Security's filter would, so it is handled here.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        log.debug("Access denied: {}", exception.getMessage());
        return respond(ErrorCode.ACCESS_DENIED, "You are not allowed to perform this action");
    }

    /**
     * Two writers modified the same row concurrently - the {@code @Version} check failed.
     *
     * <p>409, not 500: nothing is broken and the caller can reasonably re-read and retry. Spring
     * wraps Hibernate's {@link OptimisticLockException} in
     * {@link OptimisticLockingFailureException}, and which one surfaces depends on whether the write
     * went through a repository, so both are caught.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    ResponseEntity<ErrorResponse> handleOptimisticLock(Exception exception) {
        log.info("Optimistic lock conflict: {}", exception.getMessage());
        return respond(ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified by someone else. Re-read it and try again");
    }

    /**
     * A database constraint rejected the write.
     *
     * <p>Reaching this handler usually means a service-layer check and a constraint disagree, or two
     * requests raced past a check-then-insert. It is logged at warn for that reason, and the
     * constraint details are never returned - they describe the schema.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception) {
        log.warn("Data integrity violation reached the API layer", exception);
        return respond(ErrorCode.DATA_INTEGRITY_VIOLATION,
                "The request conflicts with existing data");
    }

    /** An unmapped URL. Answered in the standard shape rather than Spring's default error page. */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException exception) {
        return respond(ErrorCode.ORDER_NOT_FOUND, "No endpoint " + exception.getResourcePath());
    }

    /**
     * The catch-all. Logged in full with the stack trace, because reaching it means something
     * genuinely unexpected happened - and answered with nothing but a generic message, because
     * exception text leaks class names, SQL, file paths and occasionally credentials.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred"));
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message));
    }
}
