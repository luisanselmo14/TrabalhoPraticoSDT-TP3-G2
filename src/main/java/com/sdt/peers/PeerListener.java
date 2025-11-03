package com.sdt.peers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.ipfs.multibase.Multibase;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;


public class PeerListener {
    private static final String PUBSUB_TOPIC = "sdt_doc_updates";
    private static final String ipfsApiBase = System.getProperty("ipfs.api.base",
            System.getenv().getOrDefault("IPFS_API_BASE", "http://ipfs:5001"));

    public static void main(String[] args) {
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        while (true) {
            try {
                // encode topic in multibase Base64Url (same as DocumentManager / PeerNode)
                String encodedTopic = Multibase.encode(Multibase.Base.Base64Url, PUBSUB_TOPIC.getBytes(StandardCharsets.UTF_8));
                String url = ipfsApiBase + "/api/v0/pubsub/sub?arg=" + URLEncoder.encode(encodedTopic, StandardCharsets.UTF_8);
                URI uri = URI.create(url);
                // IPFS pubsub/sub expects POST for subscription streams
                HttpRequest req = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());

                try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                    System.out.println("PeerListener subscribed to topic " + PUBSUB_TOPIC);
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        try {
                            JsonNode wrap = mapper.readTree(line);
                            if (wrap.has("data")) {
                                String b64 = wrap.get("data").asText();
                                byte[] decoded = Base64.getDecoder().decode(b64);
                                String msgJson = new String(decoded, StandardCharsets.UTF_8);
                                System.out.println("Received pubsub message: " + msgJson);
                            } else {
                                System.out.println("Received pubsub envelope: " + wrap.toString());
                            }
                        } catch (Exception e) {
                            System.err.println("Failed to parse message: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Subscription error: " + e.getMessage());
            }

            // backoff antes de tentar reconectar
            try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            System.out.println("Reconnecting peer listener...");
        }
    }
}