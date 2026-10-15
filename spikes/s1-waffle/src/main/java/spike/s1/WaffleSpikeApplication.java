package spike.s1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Minimal service that authenticates with Waffle (Windows SSPI Negotiate/NTLM) through Spring Security. */
@SpringBootApplication
public class WaffleSpikeApplication {

    public static void main(String[] args) {
        SpringApplication.run(WaffleSpikeApplication.class, args);
    }
}
