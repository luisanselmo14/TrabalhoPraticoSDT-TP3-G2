package com.sdt.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

@Configuration
public class AppConfig {
    
    private DocumentManager documentManager;
    
    @Bean
    public IPFSClient ipfsClient() {
        String ipfsApiBase = System.getProperty("ipfs.api.base",
                System.getenv().getOrDefault("IPFS_API_BASE", "http://ipfs:5001"));
        return new IPFSClient(ipfsApiBase);
    }
    
    @Bean
    public DocumentManager documentManager(IPFSClient ipfsClient) throws Exception {
        this.documentManager = new DocumentManager(ipfsClient);
        return this.documentManager;
    }
    
    @PreDestroy
    public void cleanup() {
        if (documentManager != null) {
            System.out.println("Shutting down DocumentManager...");
            documentManager.shutdown();
        }
    }
}