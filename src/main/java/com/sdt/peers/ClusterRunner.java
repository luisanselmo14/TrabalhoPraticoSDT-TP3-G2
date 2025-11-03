package com.sdt.peers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ClusterRunner {
     public static void main(String[] args) throws Exception {
        Thread peer1 = new Thread(new PeerNode("peer-1"), "peer-1");
        Thread peer2 = new Thread(new PeerNode("peer-2"), "peer-2");
        peer1.start();
        peer2.start();

        CountDownLatch done = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown requested.");
            peer1.interrupt();
            peer2.interrupt();
            try {
                peer1.join(1000);
                peer2.join(1000);
            } catch (InterruptedException ignored) {}
            done.countDown();
        }));

        System.out.println("Peers started. Leader is the Spring Boot app; use /files/upload to add docs.");
        // aguarda até shutdown (permanecer vivo enquanto pubsub corre em background)
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // tentativa final de join antes de terminar
        try {
            peer1.join(TimeUnit.SECONDS.toMillis(1));
            peer2.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException ignored) {}
        System.out.println("ClusterRunner exiting.");
    }
}