package kz.smarthub.service;

import kz.smarthub.domain.Notification;
import kz.smarthub.domain.User;
import kz.smarthub.integration.SmsPushProvider;
import kz.smarthub.repository.NotificationRepository;
import kz.smarthub.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The in-app notification is stored with the business transaction; the SMS goes out only after it commits. */
class NotificationServiceTest {

    private NotificationRepository repo;
    private SmsPushProvider sms;
    private NotificationService service;
    private User user;

    @BeforeEach
    void setUp() {
        repo = mock(NotificationRepository.class);
        sms = mock(SmsPushProvider.class);
        // Runnable::run = deliver synchronously, so the test needs no sleeping
        service = new NotificationService(repo, mock(UserRepository.class), sms, Runnable::run);
        user = new User();
        user.setId(1L);
        user.setPhone("+77001112233");
    }

    @AfterEach
    void cleanUp() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void storesTheNotificationAndSendsTheMessageWhenThereIsNoTransaction() {
        service.notifyUser(user, 7L, "kk text", "ru text");

        verify(repo).save(any(Notification.class));
        verify(sms).send("+77001112233", "kk text / ru text");
    }

    @Test
    void aFailingProviderDoesNotBreakTheBusinessOperation() {
        doThrow(new IllegalStateException("provider down")).when(sms).send(any(), any());

        assertDoesNotThrow(() -> service.notifyUser(user, 7L, "kk", "ru"));
        verify(repo).save(any(Notification.class));
    }

    @Test
    void inATransactionTheMessageIsSentOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.notifyUser(user, 7L, "kk", "ru");

        verify(repo).save(any(Notification.class));
        verify(sms, never()).send(any(), any()); // still inside the transaction: nothing is sent yet

        List<TransactionSynchronization> callbacks = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        callbacks.forEach(TransactionSynchronization::afterCommit);

        verify(sms).send("+77001112233", "kk / ru");
    }

    @Test
    void aRolledBackTransactionSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();

        service.notifyUser(user, 7L, "kk", "ru");
        // rollback: afterCommit is never invoked, only afterCompletion(STATUS_ROLLED_BACK)
        new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(sms, never()).send(any(), any());
    }
}
