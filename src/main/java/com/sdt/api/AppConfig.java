package com.sdt.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
    
    @Bean
    public IPFSClient ipfsClient() {
        String ipfsApiBase = System.getProperty("ipfs.api.base",
                System.getenv().getOrDefault("IPFS_API_BASE", "http://ipfs:5001"));
        return new IPFSClient(ipfsApiBase);
    }
    
    @Bean
    public DocumentManager documentManager(IPFSClient ipfsClient) throws Exception {
        return new DocumentManager(ipfsClient);
    }
}