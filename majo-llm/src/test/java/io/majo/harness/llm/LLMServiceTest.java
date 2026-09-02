package io.majo.harness.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jcordis.core.context.Context;
import io.jcordis.core.registry.Plugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LLMServiceTest {

    private static LLMService booted(Context root, Object config) {
        root.plugin(new LLMServicePlugin(), config).await().join();
        root.plugin(new MockLLMPlugin(), null).await().join();
        return root.get(LLMService.NAME);
    }

    @Test
    void mockProviderDrivesTwoPhaseRoundAndEmitsEvents() {
        Context root = Context.create();
        LLMService llm = booted(root, Map.of("defaultModel", MockChatModel.MODEL_NAME));

        List<ChatRequest> requests = new ArrayList<>();
        List<String> models = new ArrayList<>();
        root.on(LlmEvents.REQUEST, (thisArg, args) -> {
            requests.add((ChatRequest) args[0]);
            models.add((String) args[1]);
            return null;
        });

        ChatRequest first = ChatRequest.of(List.of(ChatMessage.user("1+2")));
        ChatResponse roundOne = llm.complete(first);
        assertThat(roundOne.isToolRound()).isTrue();
        assertThat(roundOne.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo(MockChatModel.TOOL_NAME);
            assertThat(call.arguments()).contains("1+2");
        });

        ChatRequest second = ChatRequest.of(List.of(
                ChatMessage.user("1+2"),
                ChatMessage.toolResult(roundOne.toolCalls().get(0).id(), "3")));
        ChatResponse roundTwo = llm.complete(second);
        assertThat(roundTwo.isToolRound()).isFalse();
        assertThat(roundTwo.content()).isEqualTo("calculated: 3");

        assertThat(models).containsExactly(MockChatModel.MODEL_NAME, MockChatModel.MODEL_NAME);
        assertThat(requests).containsExactly(first, second);
        root.fiber().disposeAsync().join();
    }

    @Test
    void noModelFailsLoud() {
        Context root = Context.create();
        LLMService llm = booted(root, Map.of());
        assertThatThrownBy(() -> llm.complete(ChatRequest.of(List.of(ChatMessage.user("hi")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no model selected");
        root.fiber().disposeAsync().join();
    }

    @Test
    void registryListsModelsAndSwitchesDefaultAtRuntime() {
        Context root = Context.create();
        LLMService llm = booted(root, Map.of("defaultModel", MockChatModel.MODEL_NAME));
        llm.registerModel("fake", request -> ChatResponse.text("from-fake"));

        assertThat(llm.registeredModels()).containsExactly("fake", "mock");
        assertThat(llm.currentDefault()).isEqualTo("mock");

        llm.defaultModel("fake");
        assertThat(llm.currentDefault()).isEqualTo("fake");
        assertThat(llm.complete(ChatRequest.of(List.of(ChatMessage.user("hi")))).content())
                .isEqualTo("from-fake");

        assertThatThrownBy(() -> llm.defaultModel("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
        llm.defaultModel(null);
        assertThat(llm.currentDefault()).isNull();
        assertThatThrownBy(() -> llm.complete(ChatRequest.of(List.of(ChatMessage.user("hi")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no model selected");
        root.fiber().disposeAsync().join();
    }

    @Test
    void duplicateModelRegistrationFailsLoud() {
        Context root = Context.create();
        LLMService llm = booted(root, Map.of("defaultModel", MockChatModel.MODEL_NAME));
        assertThatThrownBy(() -> llm.registerModel(MockChatModel.MODEL_NAME, new MockChatModel()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(llm.complete(ChatRequest.of(List.of(ChatMessage.user("1+2"))))).isNotNull();
        root.fiber().disposeAsync().join();
    }

    @Test
    void pluginUnloadUnregistersModel() {
        Context root = Context.create();
        root.plugin(new LLMServicePlugin(), Map.of("defaultModel", MockChatModel.MODEL_NAME)).await().join();
        Plugin provider = new MockLLMPlugin();
        io.jcordis.core.fiber.Fiber fiber = root.plugin(provider, null).await().join();
        LLMService llm = root.get(LLMService.NAME);
        assertThat(llm.complete(ChatRequest.of(List.of(ChatMessage.user("1+2"))))).isNotNull();

        fiber.disposeAsync().join();
        assertThatThrownBy(() -> llm.complete(ChatRequest.of(List.of(ChatMessage.user("hi")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown model");
        root.fiber().disposeAsync().join();
    }
}
