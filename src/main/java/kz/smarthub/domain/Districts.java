package kz.smarthub.domain;

import java.util.List;

/** Қызылорда қаласының аудандық кодтары (интерфейсте аударылады). Маманды "жақындық" бойынша таңдау үшін. */
public final class Districts {
    public static final List<String> CODES = List.of("CENTER", "NORTH", "SOUTH", "WEST", "EAST");

    private Districts() {
    }

    public static boolean isValid(String code) {
        return code != null && CODES.contains(code);
    }
}
