package com.watertours.project.configuration.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.watertours.project.model.entity.order.TicketOrder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {



    @Bean
    public RedisTemplate<String, TicketOrder> redisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, TicketOrder> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Создаем ObjectMapper и регистрируем JavaTimeModule
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        // Передаем ObjectMapper прямо в конструктор сериализатора
        Jackson2JsonRedisSerializer<TicketOrder> serializer = new Jackson2JsonRedisSerializer<>(mapper, TicketOrder.class);

        // Настройка сериализации ключей и значений
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.setDefaultSerializer(serializer);

        return template;
    }


}