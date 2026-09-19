package com.diamondbank;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> invalid(IllegalArgumentException e) { return Map.of("message",e.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> validation(MethodArgumentNotValidException e) {
        return Map.of("message", e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField()+" "+f.getDefaultMessage()).findFirst().orElse("Invalid input."));
    }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String,String>> duplicate() {
        return ResponseEntity.status(409).body(Map.of("message","An account with this email already exists."));
    }
}
