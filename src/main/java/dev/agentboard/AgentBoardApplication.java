package dev.agentboard;

import dev.agentboard.config.BoardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BoardProperties.class)
public class AgentBoardApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentBoardApplication.class, args);
    }
}
