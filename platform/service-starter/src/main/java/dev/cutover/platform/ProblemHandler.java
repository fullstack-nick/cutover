package dev.cutover.platform;

import java.net.URI;
import org.jooq.exception.DataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ProblemHandler {
    @ExceptionHandler(Problem.class) ResponseEntity<ProblemDetail> problem(Problem failure) {
        var detail = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(failure.status()), failure.getMessage());
        detail.setType(URI.create("urn:cutover:problem:" + failure.code().toLowerCase(java.util.Locale.ROOT)));
        detail.setTitle(failure.code().replace('_', ' ')); detail.setProperty("code", failure.code());
        var response = ResponseEntity.status(failure.status());
        if (failure.status()==503 || failure.status()==429) response.header("Retry-After", "2");
        return response.body(detail);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<ProblemDetail> malformed() {
        return problem(new Problem(400, "MALFORMED_JSON", "The request body is not valid JSON for this operation."));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class}) ResponseEntity<ProblemDetail> invalid() {
        return problem(Problem.invalid("The request violates the operation's contract."));
    }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<ProblemDetail> database() {
        return problem(new Problem(503, "DATABASE_UNAVAILABLE", "The operation could not commit durably; retry with the same request key."));
    }
}
