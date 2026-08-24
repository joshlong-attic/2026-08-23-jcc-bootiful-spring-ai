package com.example.adoptions;

import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

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
                    dog.id(), dog.name(), dog.description()
            ));
            vectorStore.add(List.of(dogument));
        });


    }
}

@Controller
@ResponseBody
class DogsController {

    private final ChatClient ai;

    DogsController(ChatClient.Builder ai, VectorStore vectorStore, ToolCallbackProvider scheduler) {
        var questionAnswerAdvisor = QuestionAnswerAdvisor
                .builder(vectorStore)
                .build();
        var skills = SkillsTool
                .builder()
                .addSkillsResource(new ClassPathResource("/META-INF/skills"))
                .build();
        this.ai = ai
                .defaultTools(scheduler, skills)
                .defaultSystem("""
                                                You are an AI powered assistant to help people adopt a dog from the adoptions agency named Pooch Palace
                                                with locations in Taipei, Utrecht, Seoul, Tokyo, Singapore, Paris, Mumbai, New Delhi, Barcelona, San Francisco,
                                                and London. Information about the dogs availables will be presented below. If there is no information,
                                                then return a polite response suggesting wes don't have any dogs available.
                        
                                                If somebody asks you about animals, and there's no information in the context, then feel free to source\\s
                                                the answer from other places.
                        
                                                If somebody asks for a time to pick up the dog, don't ask other questions: simply provide a time by consulting\\s
                                                the tools you have available.
                        """)
                .defaultAdvisors(questionAnswerAdvisor)
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

