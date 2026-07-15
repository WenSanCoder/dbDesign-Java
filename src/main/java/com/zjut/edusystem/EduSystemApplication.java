package com.zjut.edusystem;

import com.zjut.edusystem.config.RedisSshProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RedisSshProperties.class)
public class EduSystemApplication {
    public static void main(String[] args) {
        SpringApplication.run(EduSystemApplication.class, args);
    }
}
