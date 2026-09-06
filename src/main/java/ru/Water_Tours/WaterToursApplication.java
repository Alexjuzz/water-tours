package ru.Water_Tours;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableScheduling
public class WaterToursApplication {

	public static void main(String[] args) {

		SpringApplication.run(WaterToursApplication.class, args);

    }

}
