package kz.smarthub.domain;

/** Тапсырыс статусы: New → Accepted → In Progress → Completed (FR-08). */
public enum OrderStatus {
    NEW("Жаңа", "Новый"),
    ACCEPTED("Қабылданды", "Принят"),
    IN_PROGRESS("Орындалуда", "В работе"),
    COMPLETED("Аяқталды", "Завершён");

    private final String labelKk;
    private final String labelRu;

    OrderStatus(String labelKk, String labelRu) {
        this.labelKk = labelKk;
        this.labelRu = labelRu;
    }

    public String getLabelKk() {
        return labelKk;
    }

    public String getLabelRu() {
        return labelRu;
    }

    /** Тізбектегі келесі статус (соңғысы үшін null). */
    public OrderStatus next() {
        int i = ordinal() + 1;
        return i < values().length ? values()[i] : null;
    }

    /** Тек бір қадам алға жылжытуға рұқсат (Accepted → Completed секіруге болмайды). */
    public boolean canMoveTo(OrderStatus target) {
        return target != null && target == next();
    }
}
