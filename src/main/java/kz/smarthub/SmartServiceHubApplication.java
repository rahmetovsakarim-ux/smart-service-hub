package kz.smarthub;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.Arrays;

@SpringBootApplication
public class SmartServiceHubApplication {

    public static void main(String[] args) {
        // Өте жаңа JDK (25, 26, 27...) кезінде Hibernate қолданатын ByteBuddy қатесіз жұмыс істесін
        System.setProperty("net.bytebuddy.experimental", "true");

        SpringApplicationBuilder builder = new SpringApplicationBuilder(SmartServiceHubApplication.class);
        // "--desktop" аргументі: компьютерлік апп режимі (жергілікті H2 файл-база + бақылау терезесі + апп терезесі)
        if (Arrays.asList(args).contains("--desktop")) {
            builder.profiles("desktop").headless(false);
        }
        builder.run(args);
    }
}
