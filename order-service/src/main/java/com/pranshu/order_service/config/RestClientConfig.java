package com.pranshu.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.core5.util.Timeout;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean(destroyMethod = "close")
    public CloseableHttpClient inventoryHttpClient(
            @Value("${inventory.client.max-connections:128}") int maxConnections) {
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(maxConnections)
                        .setMaxConnPerRoute(maxConnections)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofSeconds(1))
                                .build())
                        .build())
                // Stock mutations must never be retried automatically.
                .disableAutomaticRetries()
                .build();
    }

    @Bean
    public RestClient restClient(
            @Value("${inventory.service.url:http://localhost:8081}")
            String inventoryServiceUrl, CloseableHttpClient inventoryHttpClient) {

        var factory = new HttpComponentsClientHttpRequestFactory(inventoryHttpClient);
        // Budget: up to 1s for a pooled connection + 3s for Inventory, so Order can
        // answer 503 before a typical 5s client timeout instead of leaving it guessing.
        factory.setConnectionRequestTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(3));

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(inventoryServiceUrl)
                .build();
    }
}
