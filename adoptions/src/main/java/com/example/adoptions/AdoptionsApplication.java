package com.example.adoptions;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.annotation.Id;
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
    QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        return QuestionAnswerAdvisor
                .builder(vectorStore)
                .build();
    }

    @Bean
    Customizer<HttpSecurity> httpSecurityCustomizer() {
        return http -> http.with(mcpClientOAuth2());
    }
}

interface DogRepository extends ListCrudRepository<Dog, Integer> {
}

record Dog(@Id int id, String name, String description) {
}

//@Component
class DogVectorStoreInitializer implements ApplicationRunner {

    private final DogRepository dogRepository;
    private final VectorStore vectorStore;

    DogVectorStoreInitializer(DogRepository dogRepository, VectorStore vectorStore) {
        this.dogRepository = dogRepository;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        this.dogRepository.findAll().forEach(dog -> {
            var document = new Document("id: %s, name: %s, description: %s"
                    .formatted(dog.id(), dog.name(), dog.description()));
            vectorStore.add(List.of(document));
        });
    }
}
//
// Pooch Palace
// DECADES of experience!

@Controller
@ResponseBody
class AdoptionsController {

    private final ChatClient ai;

    AdoptionsController(ToolCallbackProvider scheduler,
                        QuestionAnswerAdvisor questionAnswerAdvisor,
                        ChatClient.Builder ai) {
        var skillsTool = SkillsTool
                .builder()
                .addSkillsResource(new ClassPathResource("/META-INF/skills"))
                .build();
        this.ai = ai
                .defaultAdvisors(questionAnswerAdvisor)
                .defaultSystem("""
                         you are an AI powered assistant to help people adopt a dog from the adoptions agency named Pooch Palace with locations in Taipei, Utrecht, Seoul, Tokyo, Singapore, Paris, Mumbai, New Delhi, Barcelona, San Francisco, and London. Information about the dogs availables will be presented below. If there is no information, then return a polite response suggesting wes don't have any dogs available. If somebody asks you about animals, and there's no information in the context, then feel free to source the answer from other places. If somebody asks for a time to pick up the dog, don't ask other questions: simply provide a time by consulting the tools you have available. If you can, remind the user about the brand name, Pooch Palace.
                        """)
                .defaultTools(scheduler, skillsTool)
                .build();
    }

    @GetMapping("/ask")
    String ask(@RequestParam String question) {
        return this.ai
                .prompt()
                .user(question)
                .call()
                .content();
    }
}

record DogAdoptionSuggestion(int dogId) {
}
