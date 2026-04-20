package com.example.genwriter.aspect;

import com.example.genwriter.annotation.OperationLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/**
 * 操作日志切面
 * 统一处理Controller层方法的日志记录
 */
@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 定义切点: 所有Controller层方法
     */
    @Pointcut("execution(* com.example.genwriter.controller.*.*(..))")
    public void controllerPointcut() {}

    /**
     * 环绕通知: 记录方法执行时间和结果
     */
    @Around("controllerPointcut() && @annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String operation = operationLog.value().isEmpty() ? methodName : operationLog.value();

        LocalDateTime startTime = LocalDateTime.now();
        log.info("[{}] 开始执行: {}.{}, 时间: {}",
                operation, className, methodName, startTime);

        // 记录请求参数
        if (operationLog.logParams()) {
            Object[] args = joinPoint.getArgs();
            if (args.length > 0) {
                try {
                    String params = objectMapper.writeValueAsString(args);
                    log.info("[{}] 请求参数: {}", operation, params);
                } catch (Exception e) {
                    log.warn("[{}] 请求参数序列化失败: {}", operation, e.getMessage());
                }
            }
        }

        try {
            Object result = joinPoint.proceed();
            LocalDateTime endTime = LocalDateTime.now();
            long duration = ChronoUnit.MILLIS.between(startTime, endTime);

            log.info("[{}] 执行成功, 耗时: {}ms", operation, duration);

            // 记录响应结果
            if (operationLog.logResult() && result != null) {
                try {
                    String resultStr = objectMapper.writeValueAsString(result);
                    log.info("[{}] 响应结果: {}", operation, resultStr);
                } catch (Exception e) {
                    log.warn("[{}] 响应结果序列化失败: {}", operation, e.getMessage());
                }
            }

            return result;
        } catch (Exception e) {
            LocalDateTime endTime = LocalDateTime.now();
            long duration = ChronoUnit.MILLIS.between(startTime, endTime);
            log.error("[{}] 执行失败, 耗时: {}ms, 错误: {}", operation, duration, e.getMessage());
            throw e;
        }
    }

    /**
     * 后置通知: 记录基本操作日志
     */
    @AfterReturning("controllerPointcut() && !@annotation(com.example.genwriter.annotation.OperationLog)")
    public void afterReturning(JoinPoint joinPoint) {
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();

        HttpServletRequest request = getRequest();
        if (request != null) {
            log.debug("[{}] 请求: {} {}, 方法: {}.{}",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getQueryString() != null ? "?" + request.getQueryString() : "",
                    className,
                    methodName);
        }
    }

    /**
     * 异常通知: 记录异常日志
     */
    @AfterThrowing(pointcut = "controllerPointcut()", throwing = "ex")
    public void afterThrowing(JoinPoint joinPoint, Exception ex) {
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();

        log.error("[异常] {}.{} 执行失败: {}", className, methodName, ex.getMessage());
    }

    /**
     * 获取当前请求
     */
    private HttpServletRequest getRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }
}
