package com.testHtmx.hello.service;

import org.springframework.stereotype.Service;

@Service
public class HelloService {


    public String getHello() {
        return "<p>Hello, это HTMX и Spring Boot! </p>";
    }
}
