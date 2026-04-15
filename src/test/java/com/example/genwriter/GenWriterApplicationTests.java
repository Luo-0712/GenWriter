package com.example.genwriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import com.example.genwriter.config.TestConfig;

@SpringBootTest
@ContextConfiguration(classes = TestConfig.class)
class GenWriterApplicationTests {

    @Test
    void contextLoads() {
    }

}
