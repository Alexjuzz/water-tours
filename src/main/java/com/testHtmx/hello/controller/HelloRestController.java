package com.testHtmx.hello.controller;


import com.testHtmx.hello.service.HelloService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloRestController {
    private final HelloService helloService;


    @Autowired
    public HelloRestController(HelloService helloService) {
        this.helloService = helloService;
    }

    @GetMapping("/api/hello")
    public String getHello(){
        return helloService.getHello();
    }
}
