package dev.sieve.server.exception;

import dev.sieve.core.ConfigurationException;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.ScreeningException;
import dev.sieve.core.SieveException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Global exception handler mapping domain exceptions to RFC 7807 Problem Detail responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final URI SIEVE_ERROR_TYPE = URI.create("https://sieve.dev/errors");

    /**
     * Handles Bean Validation failures on request DTOs.
     *
     * @param ex the validation exception
     * @return a 400 Bad Request problem detail
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(SIEVE_ERROR_TYPE + "/validation"));
        problem.setTitle("Validation Error");

        String detail =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(e -> e.getField() + ": " + e.getDefaultMessage())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("Invalid request");
        problem.setDetail(detail);

        return problem;
    }

    /**
     * Handles illegal argument exceptions (e.g., invalid enum values in path/query params).
     *
     * @param ex the exception
     * @return a 400 Bad Request problem detail
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(SIEVE_ERROR_TYPE + "/bad-request"));
        problem.setTitle("Bad Request");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Handles screening (matching) failures.
     *
     * @param ex the screening exception
     * @return a 500 Internal Server Error problem detail
     */
    @ExceptionHandler(ScreeningException.class)
    public ProblemDetail handleScreening(ScreeningException ex) {
        log.error("Screening failed", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(URI.create(SIEVE_ERROR_TYPE + "/screening"));
        problem.setTitle("Screening Error");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Handles list ingestion failures.
     *
     * @param ex the ingestion exception
     * @return a 502 Bad Gateway problem detail
     */
    @ExceptionHandler(ListIngestionException.class)
    public ProblemDetail handleIngestion(ListIngestionException ex) {
        log.error("List ingestion failed [source={}]", ex.getSource(), ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setType(URI.create(SIEVE_ERROR_TYPE + "/ingestion"));
        problem.setTitle("List Ingestion Error");
        problem.setDetail(ex.getMessage());
        if (ex.getSource() != null) {
            problem.setProperty("source", ex.getSource().name());
        }
        return problem;
    }

    /**
     * Handles configuration errors.
     *
     * @param ex the configuration exception
     * @return a 500 Internal Server Error problem detail
     */
    @ExceptionHandler(ConfigurationException.class)
    public ProblemDetail handleConfiguration(ConfigurationException ex) {
        log.error("Configuration error", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(URI.create(SIEVE_ERROR_TYPE + "/configuration"));
        problem.setTitle("Configuration Error");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /**
     * Catches any other Sieve-specific exceptions.
     *
     * @param ex the Sieve exception
     * @return a 500 Internal Server Error problem detail
     */
    @ExceptionHandler(SieveException.class)
    public ProblemDetail handleSieveException(SieveException ex) {
        log.error("Unexpected Sieve error", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(SIEVE_ERROR_TYPE);
        problem.setTitle("Internal Error");
        problem.setDetail(ex.getMessage());
        return problem;
    }
}
