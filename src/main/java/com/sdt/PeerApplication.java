package com.sdt;

import com.sdt.peers.PeerNode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
public class PeerApplication {
    
    private static final Logger logger = LoggerFactory.getLogger(PeerApplication.class);
    private static ConfigurableApplicationContext springContext;
    
    public static void main(String[] args) {
        String peerId = System.getProperty("peer.id", "peer_0");
        int totalPeers = Integer.parseInt(System.getProperty("peer.total", "3"));
        String ipfsBase = System.getProperty("ipfs.api.base", "http://ipfs:5001");
        
        logger.info("Starting peer: {}, total peers: {}", peerId, totalPeers);
        
        try {
            // Iniciar PeerNode SEM Spring Boot inicialmente
            PeerNode peerNode = new PeerNode(peerId, totalPeers, ipfsBase);
            
            // Listener de mudança de liderança
            peerNode.addLeadershipListener((newLeader, isLeader) -> {
                logger.info("Leadership changed! New leader: {}, Am I leader? {}", newLeader, isLeader);
                
                if (isLeader) {
                    logger.info("Became LEADER - starting Spring Boot API...");
                    startSpringBoot(peerNode);
                } else {
                    logger.info("Lost leadership or follower - stopping Spring Boot API...");
                    stopSpringBoot();
                }
            });
            
            // Iniciar processo de eleição
            peerNode.start();
            
            // Manter aplicação rodando
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.error("Failed to start peer", e);
            System.exit(1);
        }
    }
    
    private static synchronized void startSpringBoot(PeerNode peerNode) {
        if (springContext != null && springContext.isActive()) {
            logger.warn("Spring Boot already running, skipping...");
            return;
        }
        
        try {
            // Porta fixa para API REST (sempre 8080 internamente)
            System.setProperty("server.port", "8080");
            
            SpringApplication app = new SpringApplication(PeerApplication.class);
            app.setAdditionalProfiles("leader");
            
            springContext = app.run();
            
            // Registrar PeerNode no contexto Spring
            springContext.getBeanFactory().registerSingleton("peerNode", peerNode);
            
            logger.info("✓ Spring Boot API started on port 8080");
            
        } catch (Exception e) {
            logger.error("Failed to start Spring Boot", e);
        }
    }
    
    private static synchronized void stopSpringBoot() {
        if (springContext != null && springContext.isActive()) {
            try {
                springContext.close();
                springContext = null;
                logger.info("✓ Spring Boot API stopped");
            } catch (Exception e) {
                logger.error("Error stopping Spring Boot", e);
            }
        }
    }
}