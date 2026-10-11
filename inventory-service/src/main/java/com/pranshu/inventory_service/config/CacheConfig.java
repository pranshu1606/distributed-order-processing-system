package com.pranshu.inventory_service.config;

import com.pranshu.inventory_service.entity.Inventory;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory) {

        JacksonJsonRedisSerializer<Inventory> serializer =
                new JacksonJsonRedisSerializer<>(Inventory.class);

        RedisCacheConfiguration inventoryConfig =
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofSeconds(60))
                        .disableCachingNullValues()
                        .serializeValuesWith(
                                RedisSerializationContext.SerializationPair
                                        .fromSerializer(serializer)
                        );

        // Wait for Redis writes/eviction; transactionAware still defers them until commit.
        RedisCacheWriter cacheWriter = RedisCacheWriter.create(
                connectionFactory, config -> config.immediateWrites(true));

        return RedisCacheManager.builder(cacheWriter)
                .withCacheConfiguration("inventory", inventoryConfig)
                .disableCreateOnMissingCache()
                .transactionAware()
                .build();
    }
}
