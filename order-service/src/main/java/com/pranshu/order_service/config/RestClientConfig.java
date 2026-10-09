package com.pranshu.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient(
            @Value("${inventory.service.url:http://localhost:8081}")
            String inventoryServiceUrl) {

        return RestClient.builder()
                .baseUrl(inventoryServiceUrl)
                .build();
    }
}