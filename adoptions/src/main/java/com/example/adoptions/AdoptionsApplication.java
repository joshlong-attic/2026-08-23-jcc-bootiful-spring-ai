package com.example.adoptions;

import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

import static org.springaicommunity.mcp.security.client.sync.config.McpClientOAuth2Configurer.mcpClientOAuth2;

@SpringBootApplication
public class AdoptionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdoptionsApplication.class, args);
    }

    @Bean
    Customizer<HttpSecurity> httpSecurityCustomizer() {
        return http -> http.with(mcpClientOAuth2());
    }
}

interface DogRepository extends ListCrudRepository<Dog, Integer> {
}

// look mom, no Lombok!
record Dog(int id, String name, String description) {
}

//@Component
class VectorStoreInitializer implements InitializingBean {

    private final DogRepository repository;
    private final VectorStore vectorStore;

    VectorStoreInitializer(DogRepository repository, VectorStore vectorStore) {
        this.repository = repository;
        this.vectorStore = vectorStore;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        repository.findAll().forEach(dog -> {
            var dogument = new Document("id: %s, name: %s, description: %s".formatted(
                    dog.id(), dog.name(), dog.description()));
            vectorStore.add(List.of(dogument));
        });
    }
}

record Response(boolean proceed) {
}

class SearchRequestGateAdvisor
        implements CallAdvisor, StreamAdvisor {

    private final QuestionAnswerAdvisor questionAnswerAdvisor;
    private final ChatClient chatClient;

    SearchRequestGateAdvisor(QuestionAnswerAdvisor questionAnswerAdvisor, ChatModel model) {
        this.questionAnswerAdvisor = questionAnswerAdvisor;
        this.chatClient = ChatClient.create(model);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        var shouldContinue = proceed(chatClientRequest);
        if (shouldContinue)
            return this.questionAnswerAdvisor.adviseCall(chatClientRequest, callAdvisorChain);
        return callAdvisorChain.nextCall(chatClientRequest);
    }


    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        var shouldContinue = proceed(chatClientRequest);
        if (shouldContinue)
            return this.questionAnswerAdvisor.adviseStream(chatClientRequest, streamAdvisorChain);
        return streamAdvisorChain.nextStream(chatClientRequest);
    }

    private boolean proceed(ChatClientRequest request) {
        var prompt = request.prompt().getContents();
        var proceed = Objects.requireNonNull(chatClient
                        .prompt()
                        .user(spec -> spec //
                                .text("""
                                        does the following request look like it might be answered by searching a database for animals matching the request? if so, return true. otherwise: false.
                                        
                                        ------------------------------
                                        {request}
                                        ------------------------------
                                        """) //
                                .param("request", prompt))
                        .call()
                        .entity(Response.class))
                .proceed();
        IO.println("proceed? " + proceed);
        return proceed;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }
}

@Controller
@ResponseBody
class DogsController {

    private final ChatClient ai;

    DogsController(ChatClient.Builder ai, ChatModel chatModel, VectorStore vectorStore, ToolCallbackProvider scheduler) {
        var questionAnswerAdvisor = QuestionAnswerAdvisor
                .builder(vectorStore)
                .build();
        var searchRequestGateAdvisor = new SearchRequestGateAdvisor(
                questionAnswerAdvisor, chatModel
        );
        var skills = SkillsTool
                .builder()
                .addSkillsResource(new ClassPathResource("/META-INF/skills"))
                .build();
        var toolSearchToolCallingAdvisor = ToolSearchToolCallingAdvisor
                .builder()
                .toolIndex(new LuceneToolIndex())
                .build() ;

        this.ai = ai
                .defaultTools(scheduler, skills)
                .defaultSystem("""
                        you are an AI powered assistant to help people adopt a dog from the adoptions agency named Pooch Palace with locations
                        in Taipei, Utrecht, Seoul, Tokyo, Singapore, Paris, Mumbai, New Delhi, Barcelona, San Francisco, and London. Information
                        about the dogs availables will be presented below.
                        
                        If somebody asks you a generic question about animals, then
                        feel free to source the answer from other places like tools or skills.
                        
                        If somebody asks for a time to pick up the dog, don't ask other
                        questions: simply provide a time by consulting the tools you have available.
                        
                        If the user is searching for a dog with particular qualities, and none match, then return
                        a polite response suggesting we don't have any dogs available.
                        """)
                .defaultAdvisors( toolSearchToolCallingAdvisor ,searchRequestGateAdvisor)
                .build();
    }

    @GetMapping("/ask")
    String ask(@RequestParam String question) {
        return this.ai
                .prompt(question)
                .call()
                .content();
    }
}

