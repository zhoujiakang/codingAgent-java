package com.agentjava.conversation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话管理与消息格式转换的测试。
 */
class ConversationTest {

    @Test
    void 多轮历史按顺序累积() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("写个快排");
        conv.addAssistantMessage("好的……");
        conv.addUserMessage("改成泛型版本");

        assertEquals(3, conv.size());
        assertEquals("user", conv.getMessages().get(0).getRole().apiValue());
        assertEquals("assistant", conv.getMessages().get(1).getRole().apiValue());
        assertEquals("user", conv.getMessages().get(2).getRole().apiValue());
    }

    @Test
    void 每条内部消息都有ID与时间戳() {
        ConversationManager conv = new ConversationManager();
        Message a = conv.addUserMessage("一");
        Message b = conv.addUserMessage("二");

        assertTrue(a.getId() != null && !a.getId().isBlank());
        assertTrue(a.getTimestampMillis() > 0);
        assertEquals(Message.Status.COMPLETE, a.getStatus());
        assertEquals(a.getId() != b.getId(), true);
    }

    @Test
    void 转成API格式只有role和content() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("你好");
        conv.addAssistantMessage("你好！");

        List<Message.ApiMessage> api = conv.toApiMessages();
        assertEquals(2, api.size());
        assertEquals("user", api.get(0).role());
        assertEquals("你好", api.get(0).content());
        assertEquals("assistant", api.get(1).role());
        assertEquals("你好！", api.get(1).content());
    }

    @Test
    void 相邻的同角色消息会被合并() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("第一句");
        conv.addUserMessage("第二句");

        List<Message.ApiMessage> api = conv.toApiMessages();
        assertEquals(1, api.size(), "两条连续的 user 应合并成一条");
        assertEquals("第一句\n\n第二句", api.get(0).content());
        assertEquals("user", api.get(0).role());
    }

    @Test
    void 错误占位消息不会回传给API() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("问一句");
        Message err = conv.addErrorMessage("鉴权失败");
        assertEquals(Message.Status.ERROR, err.getStatus());

        List<Message.ApiMessage> api = conv.toApiMessages();
        assertEquals(1, api.size(), "ERROR 状态的消息必须被过滤掉");
        assertEquals("问一句", api.get(0).content());
    }

    @Test
    void 空内容消息会被过滤() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("   ");
        conv.addUserMessage("真正的问题");

        List<Message.ApiMessage> api = conv.toApiMessages();
        assertEquals(1, api.size());
        assertEquals("真正的问题", api.get(0).content());
    }

    @Test
    void 合并后仍然保证user与assistant交替() {
        ConversationManager conv = new ConversationManager();
        conv.addUserMessage("u1");
        conv.addUserMessage("u2");
        conv.addAssistantMessage("a1");
        conv.addAssistantMessage("a2");
        conv.addUserMessage("u3");

        List<Message.ApiMessage> api = conv.toApiMessages();
        assertEquals(3, api.size());
        assertEquals(List.of("user", "assistant", "user"),
                api.stream().map(Message.ApiMessage::role).toList());
    }

    @Test
    void 历史以assistant开头会被判定为非法() {
        List<Message> messages = List.of(
                new Message(Message.Role.ASSISTANT, "我先说了", Message.Status.COMPLETE),
                new Message(Message.Role.USER, "你再说", Message.Status.COMPLETE));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Message.toApiFormat(messages));
        assertTrue(e.getMessage().contains("必须以 user 消息开头"), e.getMessage());
    }
}
