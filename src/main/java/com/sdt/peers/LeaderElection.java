package com.sdt.peers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ipfs.multibase.Multibase;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

public class LeaderElection {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String ELECTION_TOPIC = "sdt_leader_election";
    private final String ipfsApiBase;
    private final String peerId;
    private final int totalPeers;
    private final String peerAddress;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(1);
    private BiConsumer<Boolean, String> electionCallback;
    
    private volatile String currentLeader;
    private volatile boolean isLeader = false;
    private volatile long lastHeartbeat = System.currentTimeMillis();
    private final long HEARTBEAT_TIMEOUT = 5000;
    private final long HEARTBEAT_INTERVAL = 2000;
    
    private volatile boolean electionInProgress = false;
    private final Set<String> electionResponses = ConcurrentHashMap.newKeySet();

    public LeaderElection(String peerId, int totalPeers, String ipfsApiBase) {
        this(peerId, totalPeers, ipfsApiBase, null, null);
    }
    
    public LeaderElection(String peerId, int totalPeers, String ipfsApiBase, 
                         String peerAddress, BiConsumer<Boolean, String> electionCallback) {
        this.peerId = peerId;
        this.totalPeers = totalPeers;
        this.ipfsApiBase = ipfsApiBase;
        this.peerAddress = peerAddress;
        this.electionCallback = electionCallback;
        startElectionSubscriber();
        startHeartbeatMonitor();
    }

    private void startElectionSubscriber() {
        executor.submit(() -> {
            try {
                String encodedTopic = Multibase.encode(Multibase.Base.Base64Url, 
                    ELECTION_TOPIC.getBytes(StandardCharsets.UTF_8));
                String urlStr = ipfsApiBase + "/api/v0/pubsub/sub?arg=" + 
                    URLEncoder.encode(encodedTopic, StandardCharsets.UTF_8);
                
                System.out.println("[" + peerId + "] Subscribing to election topic...");
                
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(0);
                
                int responseCode = conn.getResponseCode();
                if (responseCode >= 400) {
                    System.err.println("[" + peerId + "] Failed to subscribe to election topic");
                    return;
                }
                
                System.out.println("[" + peerId + "] Subscribed to election topic successfully!");
                
                try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        
                        try {
                            JsonNode wrapper = mapper.readTree(line);
                            if (wrapper.has("data")) {
                                String multibaseData = wrapper.get("data").asText();
                                byte[] decoded = Multibase.decode(multibaseData);
                                String msgJson = new String(decoded, StandardCharsets.UTF_8);
                                JsonNode node = mapper.readTree(msgJson);
                                
                                handleElectionMessage(node);
                            }
                        } catch (Exception ex) {
                            System.err.println("[" + peerId + "] Failed to parse election message: " + 
                                ex.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[" + peerId + "] Election subscriber failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void handleElectionMessage(JsonNode node) {
        String type = node.get("type").asText();
        String senderId = node.get("sender").asText();
        
        switch (type) {
            case "heartbeat":
                if (senderId.equals(currentLeader)) {
                    lastHeartbeat = System.currentTimeMillis();
                    System.out.println("[" + peerId + "] Heartbeat received from leader " + senderId);
                }
                break;
                
            case "election":
                if (peerId.compareTo(senderId) > 0) {
                    System.out.println("[" + peerId + "] Received election from " + senderId + 
                        ", responding with OK");
                    sendElectionResponse(senderId);
                    startElection();
                }
                break;
                
            case "election_ok":
                String target = node.get("target").asText();
                if (target.equals(peerId)) {
                    System.out.println("[" + peerId + "] Received OK from " + senderId);
                    electionResponses.add(senderId);
                }
                break;
                
            case "coordinator":
                boolean wasLeader = isLeader;
                System.out.println("[" + peerId + "] New leader announced: " + senderId);
                currentLeader = senderId;
                isLeader = senderId.equals(peerId);
                electionInProgress = false;
                lastHeartbeat = System.currentTimeMillis();
                
                // Notificar callback se estado mudou
                if (wasLeader && !isLeader && electionCallback != null) {
                    electionCallback.accept(false, senderId);
                } else if (!wasLeader && isLeader && electionCallback != null) {
                    electionCallback.accept(true, senderId);
                }
                break;
        }
    }

    private void startHeartbeatMonitor() {
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!isLeader && currentLeader != null) {
                    long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeat;
                    if (timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT) {
                        System.out.println("[" + peerId + "] Leader timeout detected! Starting election...");
                        currentLeader = null;
                        startElection();
                    }
                } else if (isLeader) {
                    sendHeartbeat();
                }
            } catch (Exception e) {
                System.err.println("[" + peerId + "] Heartbeat monitor error: " + e.getMessage());
            }
        }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public void startElection() {
        if (electionInProgress) {
            System.out.println("[" + peerId + "] Election already in progress, skipping");
            return;
        }
        
        electionInProgress = true;
        electionResponses.clear();
        
        System.out.println("[" + peerId + "] Starting election...");
        
        try {
            sendElectionMessage();
            Thread.sleep(3000);
            
            if (electionResponses.isEmpty()) {
                becomeLeader();
            } else {
                System.out.println("[" + peerId + "] Received " + electionResponses.size() + 
                    " responses, waiting for new leader announcement");
                Thread.sleep(5000);
                if (currentLeader == null) {
                    electionInProgress = false;
                    startElection();
                }
            }
        } catch (Exception e) {
            System.err.println("[" + peerId + "] Election error: " + e.getMessage());
            electionInProgress = false;
        }
    }

    private void becomeLeader() {
        boolean wasLeader = isLeader;
        System.out.println("[" + peerId + "] I am the new leader!");
        isLeader = true;
        currentLeader = peerId;
        electionInProgress = false;
        sendCoordinatorMessage();
        
        // Notificar callback apenas se estado mudou
        if (!wasLeader && electionCallback != null) {
            electionCallback.accept(true, peerId);
        }
    }

    private void sendElectionMessage() throws Exception {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("type", "election");
        msg.put("sender", peerId);
        publishMessage(msg);
    }

    private void sendElectionResponse(String target) {
        try {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("type", "election_ok");
            msg.put("sender", peerId);
            msg.put("target", target);
            publishMessage(msg);
        } catch (Exception e) {
            System.err.println("[" + peerId + "] Failed to send election response: " + e.getMessage());
        }
    }

    private void sendCoordinatorMessage() {
        try {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("type", "coordinator");
            msg.put("sender", peerId);
            if (peerAddress != null) {
                msg.put("address", peerAddress);
            }
            publishMessage(msg);
        } catch (Exception e) {
            System.err.println("[" + peerId + "] Failed to send coordinator message: " + e.getMessage());
        }
    }

    private void sendHeartbeat() {
        try {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("type", "heartbeat");
            msg.put("sender", peerId);
            publishMessage(msg);
        } catch (Exception e) {
            System.err.println("[" + peerId + "] Failed to send heartbeat: " + e.getMessage());
        }
    }

    private void publishMessage(ObjectNode message) throws Exception {
        String encodedTopic = Multibase.encode(Multibase.Base.Base64Url, 
            ELECTION_TOPIC.getBytes(StandardCharsets.UTF_8));
        String urlStr = ipfsApiBase + "/api/v0/pubsub/pub?arg=" + 
            URLEncoder.encode(encodedTopic, StandardCharsets.UTF_8);
        
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        
        String payloadJson = mapper.writeValueAsString(message);
        
        try (OutputStream os = conn.getOutputStream()) {
            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Disposition: form-data; name=\"data\"; filename=\"message.json\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(payloadJson.getBytes(StandardCharsets.UTF_8));
            os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        
        int responseCode = conn.getResponseCode();
        if (responseCode >= 400) {
            throw new RuntimeException("Failed to publish election message: " + responseCode);
        }
    }

    public boolean isLeader() {
        return isLeader;
    }

    public String getCurrentLeader() {
        return currentLeader;
    }

    public void waitForLeader() throws InterruptedException {
        while (currentLeader == null) {
            Thread.sleep(500);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        heartbeatExecutor.shutdownNow();
    }
}