package kz.smarthub.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Демо іске асыру: хабарламаны лог-қа жазады (нақты SMS жіберілмейді). */
@Component
public class LoggingSmsPushProvider implements SmsPushProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsPushProvider.class);

    @Override
    public void send(String phone, String message) {
        log.info("[SMS/PUSH -> {}] {}", phone, message);
    }
}
