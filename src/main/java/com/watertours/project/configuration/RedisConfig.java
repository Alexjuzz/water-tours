package com.watertours.project.configuration;

import com.watertours.project.model.entity.order.TicketOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig  {
    @Bean
    public RedisTemplate<String, TicketOrder> redisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, TicketOrder> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Используем Jackson JSON Serializer
        Jackson2JsonRedisSerializer<TicketOrder> serializer = new Jackson2JsonRedisSerializer<>(TicketOrder.class);
        template.setDefaultSerializer(serializer);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);

        return template;
    }
}
