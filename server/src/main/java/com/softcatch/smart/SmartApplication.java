package com.softcatch.smart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // 알림 스트림 하트비트
public class SmartApplication {

  public static void main(String[] args) {
    SpringApplication.run(SmartApplication.class, args);
  }
}
