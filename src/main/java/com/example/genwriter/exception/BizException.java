package com.example.genwriter.exception;

import lombok.Getter;

/**
 * 业务异常类
 * 用于封装业务逻辑错误信息
 */
@Getter
public class BizException extends RuntimeException {

    /**
     * 错误码
     */
    private final String errorCode;

    /**
     * 错误消息
     */
    private final String errorMessage;

    public BizException(String errorCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public BizException(String errorCode, String errorMessage, Throwable cause) {
        super(errorMessage, cause);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode.getCode();
        this.errorMessage = errorCode.getMessage();
    }

    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode.getCode();
        this.errorMessage = errorCode.getMessage();
    }

    /**
     * 预定义错误码枚举
     */
    @Getter
    public enum ErrorCode {
        // 系统错误
        SYSTEM_ERROR("500", "系统内部错误"),
        PARAM_ERROR("400", "参数错误"),
        UNAUTHORIZED("401", "未授权访问"),
        FORBIDDEN("403", "禁止访问"),
        NOT_FOUND("404", "资源不存在"),
        
        // 业务错误
        SESSION_NOT_FOUND("1001", "会话不存在"),
        SESSION_ALREADY_EXISTS("1002", "会话已存在"),
        MESSAGE_NOT_FOUND("1003", "消息不存在"),
        DOCUMENT_NOT_FOUND("1004", "文档不存在"),
        KNOWLEDGE_BASE_NOT_FOUND("1005", "知识库不存在"),
        KNOWLEDGE_CHUNK_NOT_FOUND("1006", "知识片段不存在"),
        TEMPLATE_NOT_FOUND("1007", "模板不存在"),
        PROJECT_NOT_FOUND("1008", "项目不存在"),
        MEMORY_NOT_FOUND("1009", "记忆不存在"),
        MEMORY_DUPLICATE("1010", "相似记忆已存在"),
        MEMORY_SCOPE_INVALID("1011", "scope 与 projectId 不一致"),
        
        // 数据操作错误
        DB_INSERT_ERROR("2001", "数据插入失败"),
        DB_UPDATE_ERROR("2002", "数据更新失败"),
        DB_DELETE_ERROR("2003", "数据删除失败"),
        DB_QUERY_ERROR("2004", "数据查询失败"),
        
        // AI服务错误
        AI_SERVICE_ERROR("3001", "AI服务调用失败"),
        EMBEDDING_ERROR("3002", "向量嵌入失败"),
        VECTOR_SEARCH_ERROR("3003", "向量搜索失败");

        private final String code;
        private final String message;

        ErrorCode(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}
