package com.agentjava.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置加载与校验的测试。
 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path write(String yaml) throws Exception {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void 合法配置能解析出providers列表() throws Exception {
        Path file = write("""
                providers:
                  - name: anthropic-official
                    protocol: anthropic
                    base_url: https://api.anthropic.com
                    api_key: sk-test-000
                    model: claude-sonnet-4-6
                    thinking: true
                  - name: openai-official
                    protocol: openai
                    api_key: sk-test-111
                    model: gpt-4o
                """);

        AppConfig config = ConfigLoader.load(file.toString());

        assertEquals(2, config.getProviders().size());
        ProviderConfig first = config.getProviders().get(0);
        assertEquals("anthropic-official", first.getName());
        assertEquals("anthropic", first.normalizedProtocol());
        assertEquals("https://api.anthropic.com", first.getBaseUrl());
        assertEquals("sk-test-000", first.getApiKey());
        assertEquals("claude-sonnet-4-6", first.getModel());
        assertTrue(first.isThinking());

        // 第二项没写 thinking，应为默认的 false
        assertFalse(config.getProviders().get(1).isThinking());
        // 第二项没写 base_url，应为空（由适配器回退到 SDK 默认端点）
        assertEquals(null, config.getProviders().get(1).getBaseUrl());
    }

    @Test
    void 协议名大小写与空格不影响解析() throws Exception {
        Path file = write("""
                providers:
                  - name: p
                    protocol: "  OpenAI-Compat  "
                    base_url: https://api.example.com/v1
                    api_key: k
                    model: m
                """);
        ProviderConfig cfg = ConfigLoader.load(file.toString()).getProviders().get(0);
        assertEquals("openai-compat", cfg.normalizedProtocol());
    }

    @Test
    void 文件缺失时给出可读错误() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(tempDir.resolve("nope.yaml").toString()));
        assertTrue(e.getMessage().contains("找不到配置文件"), e.getMessage());
    }

    @Test
    void providers为空时报错() throws Exception {
        Path file = write("providers: []\n");
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(file.toString()));
        assertTrue(e.getMessage().contains("providers"), e.getMessage());
    }

    @Test
    void 缺少api_key时点名具体字段() throws Exception {
        Path file = write("""
                providers:
                  - name: p
                    protocol: anthropic
                    model: m
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(file.toString()));
        assertEquals("providers[0].api_key 不能为空。", e.getMessage());
    }

    @Test
    void 数组下标随位置变化() throws Exception {
        Path file = write("""
                providers:
                  - name: a
                    protocol: anthropic
                    api_key: k
                    model: m
                  - name: b
                    protocol: openai
                    model: m2
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(file.toString()));
        assertEquals("providers[1].api_key 不能为空。", e.getMessage());
    }

    @Test
    void 未知协议时报错并列出可选值() throws Exception {
        Path file = write("""
                providers:
                  - name: p
                    protocol: gemini
                    api_key: k
                    model: m
                """);
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(file.toString()));
        assertTrue(e.getMessage().contains("protocol 不合法"), e.getMessage());
        assertTrue(e.getMessage().contains("anthropic"), e.getMessage());
    }

    @Test
    void 非法的YAML不会抛出解析栈() throws Exception {
        Path file = write("providers: [ - name: 'unterminated\n");
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(file.toString()));
        assertTrue(e.getMessage().contains("不是合法的 YAML"), e.getMessage());
    }

    @Test
    void toString不泄露apiKey() {
        ProviderConfig cfg = new ProviderConfig("p", "anthropic", null, "sk-secret-123", "m", false);
        String s = cfg.toString();
        assertFalse(s.contains("sk-secret-123"), "toString 不应包含密钥");
        assertTrue(s.contains("anthropic"));
    }
}
