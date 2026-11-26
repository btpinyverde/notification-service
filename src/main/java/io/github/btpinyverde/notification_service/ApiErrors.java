package io.github.btpinyverde.notification_service;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Arrays;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

@RestControllerAdvice 
class ApiErrors extends ResponseEntityExceptionHandler {
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Revisá los campos de la solicitud");
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldProblem(error.getField(), error.getDefaultMessage())).toList());
        return handleExceptionInternal(exception, problem, headers, status, request);
    }   

    @Override 
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = "Se esperaba un objeto Json con campos del tipo indicado";
        if (exception.getCause() instanceof InvalidFormatException invalid && invalid.getTargetType().isEnum()
            && !invalid.getPath().isEmpty()) {
            detail = invalid.getPath().get(0).getFieldName() + " debe ser uno de "
                + Arrays.toString(invalid.getTargetType().getEnumConstants());
            }
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
            return handleExceptionInternal(exception, problem, headers, status, request);

    }

    record FieldProblem(String field, String message) {
    }
}
