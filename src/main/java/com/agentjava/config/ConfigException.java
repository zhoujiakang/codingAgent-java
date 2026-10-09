package com.agentjava.config;

/**
 * 配置异常。
 *
 * 用于把配置文件缺失、YAML 格式错误、字段缺失或不合法等启动期问题，
 * 转换成一句可读的中文提示，而不是抛出调用栈吓用户。
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
