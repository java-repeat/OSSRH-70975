package demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        // One Netty event-loop thread, shared by server and WebClient (the WebFlux default).
        System.setProperty("reactor.netty.ioWorkerCount", "1");
        SpringApplication.run(DemoApplication.class, args);
    }
}
