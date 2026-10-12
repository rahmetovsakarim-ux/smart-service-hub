package kz.smarthub.integration;

/** Сыртқы SMS/Push провайдерімен интерфейс (Lab-4 Class диаграммасы). Нақты провайдерге ауыстыру үшін осыны іске асырыңыз. */
public interface SmsPushProvider {

    void send(String phone, String message);
}
