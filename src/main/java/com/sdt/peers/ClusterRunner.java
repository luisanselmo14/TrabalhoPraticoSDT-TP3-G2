package com.sdt.peers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ClusterRunner {
     public static void main(String[] args) throws Exception {
        PeerNode peerNode1 = new PeerNode("peer-1");
        PeerNode peerNode2 = new PeerNode("peer-2");
        Thread peer1 = new Thread(peerNode1, "peer-1");
        Thread peer2 = new Thread(peerNode2, "peer-2");
        peer1.start();
        peer2.start();

        CountDownLatch done = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown requested.");
            peerNode1.shutdown();
            peerNode2.shutdown();
            peer1.interrupt();
            peer2.interrupt();
            try {
                peer1.join(1000);
                peer2.join(1000);
            } catch (InterruptedException ignored) {}
            done.countDown();
        }));

        System.out.println("Peers started. Leader is the Spring Boot app; use /files/upload to add docs.");
        System.out.println("Heartbeat interval: " + System.getProperty("heartbeat.interval.seconds", "5") + " seconds");
        System.out.println("Failure detection timeout: " + System.getProperty("heartbeat.timeout.seconds", "15") + " seconds");
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