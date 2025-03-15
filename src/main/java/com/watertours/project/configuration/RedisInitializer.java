package com.watertours.project.configuration;

import com.watertours.project.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;

public class RedisInitializer implements CommandLineRunner {
    private final OrderService orderService;

    @Autowired
    public RedisInitializer(OrderService orderService) {
        this.orderService = orderService;
    }
    @Override
    public void run(String... args) throws Exception {
        orderService.clearAllCartsFromRedis();
    }
}
