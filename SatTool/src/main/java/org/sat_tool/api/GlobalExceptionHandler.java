package org.sat_tool.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.orekit.errors.OrekitException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

/**
 * REST API 공통 예외 처리. 잘못된 입력(TLE 파싱 실패, 시각 포맷 오류 등)은 400으로,
 * 그 외 예기치 못한 오류는 상세를 숨기고 500으로 응답한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(String code, String message, Map<String, String> details) {
        static ErrorResponse of(String code, String message) {
            return new ErrorResponse(code, message, null);
        }
    }

    /** @Valid 바인딩 실패 → 필드별 오류 메시지 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> details = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fe ->
                details.put(fe.getField(), fe.getDefaultMessage()));
        return new ErrorResponse("VALIDATION_ERROR", "request validation failed", details);
    }

    /** 잘못된 TLE(OrekitIllegalArgumentException 포함), 시각 포맷 오류 등 입력 문제 */
    @ExceptionHandler({IllegalArgumentException.class, OrekitException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadInput(RuntimeException e) {
        return ErrorResponse.of("INVALID_INPUT", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpected(Exception e) {
        log.error("unhandled exception in REST API", e);
        return ErrorResponse.of("INTERNAL_ERROR", "unexpected server error");
    }
}
