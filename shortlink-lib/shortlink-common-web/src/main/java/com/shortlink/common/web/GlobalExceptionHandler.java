package com.shortlink.common.web;

import com.shortlink.common.core.exception.BizException;
import com.shortlink.common.core.result.ErrorCode;
import com.shortlink.common.core.result.Result;
import com.shortlink.common.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（Servlet 栈）。
 *
 * <p>三条原则：</p>
 * <ol>
 *   <li>业务异常只输出“用户能看懂的话”，不暴露堆栈；</li>
 *   <li>未知异常统一 500 + 错误码，堆栈只进日志，并带上 traceId 方便检索；</li>
 *   <li>响应体与成功响应同构（Result），前端只需判断 code。</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException ex) {
        log.warn("biz exception code={} msg={}", ex.getCode(), ex.getMessage());
        Result<Void> body = Result.<Void>fail(ex.getErrorCode(), ex.getMessage())
                .withTraceId(TraceContext.get());
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse(ErrorCode.PARAM_INVALID.message());
        return badRequest(detail);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse(ErrorCode.PARAM_INVALID.message());
        return badRequest(detail);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("缺少参数: " + ex.getParameterName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return badRequest(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        log.error("unexpected exception, traceId={}", TraceContext.get(), ex);
        Result<Void> body = Result.<Void>fail(ErrorCode.SYSTEM_ERROR).withTraceId(TraceContext.get());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<Result<Void>> badRequest(String detail) {
        Result<Void> body = Result.<Void>fail(ErrorCode.PARAM_INVALID, detail).withTraceId(TraceContext.get());
        return ResponseEntity.badRequest().body(body);
    }
}
