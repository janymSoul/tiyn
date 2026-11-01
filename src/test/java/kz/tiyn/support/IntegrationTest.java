package kz.tiyn.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import({PostgresTestConfig.class, FakeGoogleConfig.class})
@TestPropertySource(
        properties = {
            "tiyn.jwt.secret=" + TestTokens.SECRET,
            "tiyn.google.client-ids=test-client-id",
            "tiyn.tick.interval=PT1H"
        })
public @interface IntegrationTest {}
