package com.sdt.peers;

public class ClusterRunner {
    public static void main(String[] args) throws Exception {
        Thread peer1 = new Thread(new PeerNode("peer-1"), "peer-1");
        Thread peer2 = new Thread(new PeerNode("peer-2"), "peer-2");
        peer1.start();
        peer2.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown requested.");
            peer1.interrupt();
            peer2.interrupt();
            try {
                peer1.join(1000);
                peer2.join(1000);
            } catch (InterruptedException ignored) {}
        }));

        System.out.println("Peers started. Leader is the Spring Boot app; use /files/upload to add docs.");
        peer1.join();
    }
}