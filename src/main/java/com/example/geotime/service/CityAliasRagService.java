package com.example.geotime.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import io.micrometer.observation.annotation.Observed;

@Service
public class CityAliasRagService {

    private static final Logger log = LoggerFactory.getLogger(CityAliasRagService.class);
    private final VectorStore vectorStore;

    public CityAliasRagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Bean
    public ApplicationRunner seedMilvusCityAliases() {
        return args -> {
            List<Document> existing = vectorStore.similaritySearch(
                SearchRequest.builder().query("The Big Easy").topK(1).build()
            );
            if (!existing.isEmpty()) {
                log.info("Milvus collection 'city_aliases' already seeded.");
                return;
            }

            List<Document> aliases = List.of(
                new Document("The Big Easy, Crescent City, NOLA, MSY airport", Map.of("canonical", "New Orleans, Louisiana")),
                new Document("Music City, Smashville, BNA airport", Map.of("canonical", "Nashville, Tennessee")),
                new Document("Motor City, Motown, Hockeytown, DTW airport", Map.of("canonical", "Detroit, Michigan")),
                new Document("The Windy City, Chi-Town, Second City, ORD airport", Map.of("canonical", "Chicago, Illinois")),
                new Document("The Big Apple, Gotham, Empire City, JFK airport", Map.of("canonical", "New York, New York")),
                new Document("Beantown, The Hub, Cradle of Liberty, BOS airport", Map.of("canonical", "Boston, Massachusetts")),
                new Document("Emerald City, Jet City, Rain City, SEA airport", Map.of("canonical", "Seattle, Washington")),
                new Document("Mile High City, Queen City of the Plains, DEN airport", Map.of("canonical", "Denver, Colorado")),
                new Document("City of Brotherly Love, Philly, PHL airport", Map.of("canonical", "Philadelphia, Pennsylvania")),
                new Document("Space City, H-Town, Bayou City, IAH airport", Map.of("canonical", "Houston, Texas")),
                new Document("The Magic City, 305, Vice City, MIA airport", Map.of("canonical", "Miami, Florida")),
                new Document("City of Angels, Tinseltown, LA, LAX airport", Map.of("canonical", "Los Angeles, California")),
                new Document("Sin City, Entertainment Capital of the World, LAS airport", Map.of("canonical", "Las Vegas, Nevada")),
                new Document("Steel City, City of Bridges, Iron City, PIT airport", Map.of("canonical", "Pittsburgh, Pennsylvania")),
                new Document("River City, RVA, Capital of the Confederacy, RIC airport", Map.of("canonical", "Richmond, Virginia"))
            );

            vectorStore.add(aliases);
            log.info("Seeded {} U.S. city aliases into Milvus vector store!", aliases.size());
        };
    }
    
    @Observed(name = "agent.stage.3.rag-fallback")
    @Tool(description = "Semantic RAG fallback tool: resolves U.S. city nicknames, cultural aliases, or airport codes (e.g., 'The Big Easy', 'Music City', 'Motor City') into canonical 'City, State' format using Milvus vector similarity.")
    public String resolveCityAlias(
        @ToolParam(description = "The city nickname, cultural alias, or airport code to resolve") String aliasQuery
    ) {
        List<Document> matches = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(aliasQuery)
                .topK(1)
                .similarityThreshold(0.55)
                .build()
        );

        if (matches.isEmpty()) {
            log.info("Milvus RAG fallback found no semantic match for '{}'", aliasQuery);
            return "";
        }

        String canonical = String.valueOf(matches.get(0).getMetadata().get("canonical"));
        log.info("MILVUS AGENTIC RAG HIT: '{}' -> '{}'", aliasQuery, canonical);
        return canonical;
    }
}
