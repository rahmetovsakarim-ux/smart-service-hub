# Тапсырыс статусының тізбегі (FR-08)

```mermaid
stateDiagram-v2
    [*] --> NEW: Клиент тапсырыс берді
    NEW --> NEW: Маман бас тартты → Әкімшіге escalation
    NEW --> ACCEPTED: Маман қабылдады
    ACCEPTED --> IN_PROGRESS: Жұмыс басталды
    IN_PROGRESS --> COMPLETED: Жұмыс аяқталды
    COMPLETED --> [*]: Төлем + баға
```
