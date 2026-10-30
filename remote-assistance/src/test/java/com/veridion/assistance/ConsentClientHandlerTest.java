package com.veridion.assistance;

import java.rmi.server.UnicastRemoteObject;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsentClientHandlerTest {
    private static final String TOKEN = "test-operator-token-long-enough-0123456789";
    private ConsentClientHandler handler;
    private List<ConsentClientHandler> handlers;

    @BeforeEach void setUp() throws RemoteException {
        handlers = new ArrayList<>();
        handler = newHandler(operator -> true);
    }

    @AfterEach void tearDown() {
        for (ConsentClientHandler activeHandler : handlers) {
            try { activeHandler.terminateSession(activeHandler.getActiveSessionId(), TOKEN); }
            catch (Exception ignored) { }
            try { UnicastRemoteObject.unexportObject(activeHandler, true); }
            catch (Exception ignored) { }
        }
    }

    private ConsentClientHandler newHandler(Predicate<String> consent) throws RemoteException {
        return newHandler(consent, consent);
    }

    private ConsentClientHandler newHandler(Predicate<String> consent, Predicate<String> inputConsent)
            throws RemoteException {
        ConsentClientHandler created = new ConsentClientHandler(consent, inputConsent, TOKEN);
        handlers.add(created);
        return created;
    }

    private static boolean awaitPrompt(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("test did not release consent prompt");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test void consentStartsOneSessionAndTerminationEndsItImmediately() throws Exception {
        String sessionId = handler.requestAssistance("Support operator", TOKEN);
        assertNotNull(sessionId);
        assertFalse(sessionId.isEmpty());
        assertEquals(sessionId, UUID.fromString(sessionId).toString());
        assertTrue(handler.isSessionActive());
        assertEquals(ConsentClientHandler.SessionState.ACTIVE_VIEW, handler.getState());
        assertEquals("", handler.requestAssistance("Another operator", TOKEN));
        assertThrows(java.rmi.RemoteException.class, () -> handler.terminateSession(sessionId, "wrong"));
        handler.terminateSession(sessionId, TOKEN);
        assertFalse(handler.isSessionActive());
        assertNull(handler.getActiveSessionId());
        assertEquals(ConsentClientHandler.SessionState.TERMINATED, handler.getState());
    }

    @Test void deniedConsentDoesNotCreateSession() throws Exception {
        handler = newHandler(operator -> false);
        assertThrows(java.rmi.RemoteException.class, () -> handler.requestAssistance("Support operator", "wrong"));
        assertEquals("", handler.requestAssistance("Support operator", TOKEN));
        assertFalse(handler.isSessionActive());
        assertNull(handler.getActiveSessionId());
        assertEquals(ConsentClientHandler.SessionState.IDLE, handler.getState());
        assertFalse(handler.getAuditLog().getEntries().stream()
                .anyMatch(entry -> entry.action().equals("SESSION_STARTED")));
    }

    @Test void terminationInvalidatesLateAcceptedSessionPrompt() throws Exception {
        CountDownLatch promptEntered = new CountDownLatch(1);
        CountDownLatch releasePrompt = new CountDownLatch(1);
        handler = newHandler(name -> awaitPrompt(promptEntered, releasePrompt), name -> false);
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
            try { return handler.requestAssistance("Operator A", TOKEN); }
            catch (RemoteException e) { throw new java.util.concurrent.CompletionException(e); }
        });

        try {
            assertTrue(promptEntered.await(2, TimeUnit.SECONDS));
            handler.terminateSession(null, TOKEN);
            releasePrompt.countDown();
            assertEquals("", result.get(2, TimeUnit.SECONDS));
            assertEquals(ConsentClientHandler.SessionState.TERMINATED, handler.getState());
            assertNull(handler.getActiveSessionId());
        } finally {
            releasePrompt.countDown();
        }
    }

    @Test void staleSessionPromptCannotReplaceNewerRequest() throws Exception {
        CountDownLatch promptAEntered = new CountDownLatch(1);
        CountDownLatch releasePromptA = new CountDownLatch(1);
        CountDownLatch promptBEntered = new CountDownLatch(1);
        CountDownLatch releasePromptB = new CountDownLatch(1);
        AtomicInteger promptCount = new AtomicInteger();
        handler = newHandler(name -> promptCount.incrementAndGet() == 1
                ? awaitPrompt(promptAEntered, releasePromptA)
                : awaitPrompt(promptBEntered, releasePromptB), name -> false);

        CompletableFuture<String> resultA = CompletableFuture.supplyAsync(() -> {
            try { return handler.requestAssistance("Operator A", TOKEN); }
            catch (RemoteException e) { throw new java.util.concurrent.CompletionException(e); }
        });
        CompletableFuture<String> resultB = null;
        try {
            assertTrue(promptAEntered.await(2, TimeUnit.SECONDS));
            handler.terminateSession(null, TOKEN);
            resultB = CompletableFuture.supplyAsync(() -> {
                try { return handler.requestAssistance("Operator B", TOKEN); }
                catch (RemoteException e) { throw new java.util.concurrent.CompletionException(e); }
            });
            assertTrue(promptBEntered.await(2, TimeUnit.SECONDS));

            releasePromptA.countDown();
            assertEquals("", resultA.get(2, TimeUnit.SECONDS));
            assertEquals(ConsentClientHandler.SessionState.REQUESTING_CONSENT, handler.getState());
            assertNull(handler.getActiveSessionId());

            releasePromptB.countDown();
            String sessionIdB = resultB.get(2, TimeUnit.SECONDS);
            assertNotNull(sessionIdB);
            assertFalse(sessionIdB.isBlank());
            assertEquals(sessionIdB, handler.getActiveSessionId());
            assertEquals(ConsentClientHandler.SessionState.ACTIVE_VIEW, handler.getState());
            assertTrue(handler.getAuditLog().getEntries().stream()
                    .anyMatch(entry -> entry.action().equals("SESSION_STARTED")
                            && entry.operatorId().equals("Operator B")));
        } finally {
            releasePromptA.countDown();
            releasePromptB.countDown();
        }
    }

    @Test void terminationInvalidatesLateAcceptedInputPrompt() throws Exception {
        CountDownLatch promptEntered = new CountDownLatch(1);
        CountDownLatch releasePrompt = new CountDownLatch(1);
        handler = newHandler(name -> true, name -> awaitPrompt(promptEntered, releasePrompt));
        String sessionId = handler.requestAssistance("Operator", TOKEN);
        CompletableFuture<java.rmi.RemoteException> result = CompletableFuture.supplyAsync(() -> {
            try {
                handler.requestInputControl(sessionId, TOKEN);
                return null;
            } catch (java.rmi.RemoteException e) {
                return e;
            }
        });

        try {
            assertTrue(promptEntered.await(2, TimeUnit.SECONDS));
            handler.terminateSession(sessionId, TOKEN);
            releasePrompt.countDown();
            java.rmi.RemoteException error = result.get(2, TimeUnit.SECONDS);
            assertNotNull(error);
            assertTrue(error.getMessage().contains("Session ended while input consent was pending"));
            assertEquals(ConsentClientHandler.SessionState.TERMINATED, handler.getState());
            assertNull(handler.getActiveSessionId());
        } finally {
            releasePrompt.countDown();
        }
    }
}
