package com.claimlens.config;

import com.claimlens.guardrail.CitationValidator;
import com.claimlens.guardrail.InjectionDetector;
import com.claimlens.guardrail.RoutingPolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AiConfig {

    /** Main chat client used to draft decisions. SimpleLoggerAdvisor logs prompts at DEBUG for auditing. */
    @Bean
    ChatClient claimsChatClient(ChatClient.Builder builder) {
        return builder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    /** In-memory vector store for the default profile (no database needed). */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "simple", matchIfMissing = false)
    VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /** "Is the answer relevant to the question, given the retrieved context?" */
    @Bean
    RelevancyEvaluator relevancyEvaluator(ChatClient.Builder builder) {
        return new RelevancyEvaluator(builder);
    }

    /** "Is every statement in the answer supported by the retrieved documents?" */
    @Bean
    FactCheckingEvaluator factCheckingEvaluator(ChatClient.Builder builder) {
        return FactCheckingEvaluator.builder(builder).build();
    }

    @Bean
    CitationValidator citationValidator() {
        return new CitationValidator();
    }

    @Bean
    InjectionDetector injectionDetector() {
        return new InjectionDetector();
    }

    @Bean
    RoutingPolicy routingPolicy(ClaimLensProperties props) {
        return new RoutingPolicy(props.guardrails().minModelConfidence(), props.guardrails().highValueThreshold());
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService pipelineExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
