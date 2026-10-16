package com.veridion.server;

import com.veridion.shared.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import static org.junit.jupiter.api.Assertions.*;

class LoadTest {
    private ActivityServerImpl server;
    private static final int NUM_CLIENTS = 100;
    private static final int EVENTS_PER_CLIENT = 100;

    @BeforeEach
    void setup() throws Exception {
        // Create a mock enrollment map for 100 clients
        Map<String, String> tokens = new HashMap<>();
        for (int i = 0; i < NUM_CLIENTS; i++) {
            tokens.put("client-" + i, "token-secret-long-enough-for-security-0" + i);
        }
        server = new ActivityServerImpl(tokens, 0, Duration.ofSeconds(30));
    }

    @Test
    void testConcurrentEnrollmentAndReporting() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(NUM_CLIENTS);
        LongAdder totalEvents = new LongAdder();

        for (int i = 0; i < NUM_CLIENTS; i++) {
            final String id = "client-" + i;
            final String token = "token-secret-long-enough-for-security-0" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // 1. Enroll
                    String cred = server.registerClient(id, "load-test-host", token);
                    assertNotNull(cred);

                    // 2. Report burst of events
                    for (int j = 0; j < EVENTS_PER_CLIENT; j++) {
                        server.reportActivity(new ActivityEvent(id, "LoadApp", "Window-" + j, java.time.LocalDateTime.now()), cred);
                        totalEvents.increment();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        long start = System.currentTimeMillis();
        startLatch.countDown(); // GO
        boolean finished = endLatch.await(30, TimeUnit.SECONDS);
        long end = System.currentTimeMillis();

        executor.shutdown();
        
        assertTrue(finished, "Load test timed out");
        assertEquals(NUM_CLIENTS * EVENTS_PER_CLIENT, totalEvents.sum(), "Missing events");
        
        System.out.println("Load Test Results:");
        System.out.println("Clients: " + NUM_CLIENTS);
        System.out.println("Events/Client: " + EVENTS_PER_CLIENT);
        System.out.println("Total Events: " + totalEvents.sum());
        System.out.println("Total Time: " + (end - start) + "ms");
        System.out.println("Throughput: " + (totalEvents.sum() / ((end - start) / 1000.0)) + " events/sec");

        // Verify a random client's log size
        var randomLog = server.getLogsForClient("client-50");
        assertNotNull(randomLog);
        assertTrue(randomLog.size() <= 5000); // Bound check
    }
}
