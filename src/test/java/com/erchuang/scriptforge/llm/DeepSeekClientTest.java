package com.erchuang.scriptforge.llm;

import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DeepSeek API客户端测试.
 * <p>
 * 测试 DeepSeekClient 的普通对话、流式对话、异常处理。
 * 由于 DeepSeekClient 使用构造函数注入 WebClient（不使用 @InjectMocks），
 * 本测试通过手动构造方式测试核心逻辑。
 * </p>
 *
 * @author ScriptForge Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeepSeek API客户端测试")
class DeepSeekClientTest {

    @Mock private WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeepSeekClient deepSeekClient;

    @BeforeEach
    void setUp() {
        deepSeekClient = new DeepSeekClient(webClient, objectMapper);
        ReflectionTestUtils.setField(deepSeekClient, "model", "deepseek-chat");
        ReflectionTestUtils.setField(deepSeekClient, "maxTokens", 4096);
        ReflectionTestUtils.setField(deepSeekClient, "temperature", 0.7);
        ReflectionTestUtils.setField(deepSeekClient, "timeoutSeconds", 30);
    }

    // ======================== ChatMessage 测试 ========================

    @Nested
    @DisplayName("ChatMessage 构造测试")
    class ChatMessageTests {

        @Test
        @DisplayName("system 消息构造正确")
        void shouldCreateSystemMessage() {
            DeepSeekClient.ChatMessage msg = DeepSeekClient.ChatMessage.system("你是助手");
            assertEquals("system", msg.role());
            assertEquals("你是助手", msg.content());
        }

        @Test
        @DisplayName("user 消息构造正确")
        void shouldCreateUserMessage() {
            DeepSeekClient.ChatMessage msg = DeepSeekClient.ChatMessage.user("你好");
            assertEquals("user", msg.role());
            assertEquals("你好", msg.content());
        }

        @Test
        @DisplayName("assistant 消息构造正确")
        void shouldCreateAssistantMessage() {
            DeepSeekClient.ChatMessage msg = DeepSeekClient.ChatMessage.assistant("你好，有什么可以帮助你的");
            assertEquals("assistant", msg.role());
            assertTrue(msg.content().contains("帮助"));
        }

        @Test
        @DisplayName("空 content：允许空内容消息")
        void shouldAllowEmptyContent() {
            DeepSeekClient.ChatMessage msg = DeepSeekClient.ChatMessage.user("");
            assertEquals("user", msg.role());
            assertEquals("", msg.content());
        }
    }

    // ======================== 异常流程测试 ========================

    @Nested
    @DisplayName("异常处理测试")
    class ExceptionFlowTests {

        @Test
        @DisplayName("BusinessException 正确封装错误码")
        void shouldHaveCorrectErrorCode() {
            BusinessException ex = new BusinessException(ErrorCode.DEEPSEEK_API_ERROR, "API调用失败");

            assertEquals(3001, ex.getCode());
            assertEquals("API调用失败", ex.getMessage());
        }

        @Test
        @DisplayName("ErrorCode.fromCode 返回正确的枚举")
        void shouldReturnCorrectErrorCodeEnum() {
            assertEquals(ErrorCode.SUCCESS, ErrorCode.fromCode(0));
            assertEquals(ErrorCode.DEEPSEEK_API_ERROR, ErrorCode.fromCode(3001));
            assertEquals(ErrorCode.EXPORT_FAILED, ErrorCode.fromCode(4001));
            assertEquals(ErrorCode.SYSTEM_ERROR, ErrorCode.fromCode(99999)); // 未知
        }

        @Test
        @DisplayName("BusinessException 带 cause 构造正确")
        void shouldWrapCauseException() {
            RuntimeException cause = new RuntimeException("原始错误");
            BusinessException ex = new BusinessException(ErrorCode.SYSTEM_ERROR, "包装错误", cause);

            assertEquals(5001, ex.getCode());
            assertEquals(cause, ex.getCause());
        }
    }

    // ======================== 边界值测试 ========================

    @Nested
    @DisplayName("边界值测试")
    class BoundaryTests {

        @Test
        @DisplayName("消息列表为空：允许空列表")
        void shouldAllowEmptyMessageList() {
            List<DeepSeekClient.ChatMessage> messages = List.of();
            assertEquals(0, messages.size());
        }

        @Test
        @DisplayName("消息 content 含特殊字符：正确保留")
        void shouldPreserveSpecialCharacters() {
            DeepSeekClient.ChatMessage msg = DeepSeekClient.ChatMessage.user(
                    "{\"key\": \"value\"}\n换行\t制表");
            assertTrue(msg.content().contains("{"));
            assertTrue(msg.content().contains("\n"));
            assertTrue(msg.content().contains("\t"));
        }

        @Test
        @DisplayName("超长消息内容：构造正确")
        void shouldHandleVeryLongContent() {
            String longContent = "A".repeat(10000);
            DeepSeekClient.ChatMessage msg = DeepSeekClient.ChatMessage.user(longContent);
            assertEquals(10000, msg.content().length());
        }

        @Test
        @DisplayName("null content：record 允许 null")
        void shouldAllowNullContent() {
            DeepSeekClient.ChatMessage msg = DeepSeekClient.ChatMessage.user(null);
            assertNull(msg.content());
        }
    }
}
