package com.example.geotime.config;

import com.example.geotime.tools.GeoTimeTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider geoTimeToolCallbackProvider(GeoTimeTools geoTimeTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(geoTimeTools)
            .build();
    }
}
