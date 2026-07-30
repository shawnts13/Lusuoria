package com.lusuoria.settlement.exception;

import com.lusuoria.settlement.dto.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleRuntimeException(RuntimeException e) {
        // 业务里主动抛的 RuntimeException（比如"品牌方不存在"）message 都不为空，直接展示即可；
        // 意外的空指针/类型转换这类"没预料到"的异常 message 经常是 null，之前会导致前端只显示
        // 兜底的"请求参数错误"、又查不到原因——这里兜底用异常类名+堆栈第一行，并且打印到日志，
        // 方便照着 Render 日志定位具体是哪一行代码抛出来的。
        if (e.getMessage() == null) {
            log.error("未预期的 RuntimeException：{}", e.toString(), e);
            return ApiResponse.error(400, "服务器处理异常：" + e.toString());
        }
        // e.getCause() 非空说明这条 RuntimeException 是 catch 块里包装了某个底层技术异常
        // （IO/序列化/第三方SDK等）抛出来的，不是纯业务校验失败——虽然 message 本身可读，
        // 但如果这里不打印，底层真正的异常类型和堆栈就永久丢失了：前端只会看到这句包装消息，
        // Render 日志里也查不到具体是哪一行、哪种异常导致的（这条系统里已经踩过好几次这个坑，
        // 比如 GoogleDriveAuthService/PayslipService 里包装 IOException/序列化异常抛出的
        // RuntimeException，之前完全没有日志痕迹）。
        if (e.getCause() != null) {
            log.error("业务异常（附带底层异常，见栈底）：{}", e.toString(), e);
        }
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ApiResponse.error(400, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("未预期的异常：{}", e.toString(), e);
        return ApiResponse.error(500, "服务器错误：" + e.getMessage());
    }
}