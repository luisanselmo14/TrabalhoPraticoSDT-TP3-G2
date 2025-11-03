package com.sdt.peers;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;


public class LeaderSender {
    private final String PUBSUB_TOPIC = "sdt_doc_updates";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final URI ipfsApiBase = URI.create("http://127.0.0.1:5001");
    private final ObjectMapper mapper = new ObjectMapper();

    public void publish(int version, String cid, List<String> vector, float[] embedding) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "doc_update");
            root.put("version", version);
            root.put("cid", cid);
            root.set("vector", mapper.valueToTree(vector));
            root.set("embedding", mapper.valueToTree(embedding));

            byte[] payload = mapper.writeValueAsBytes(root);
            URI uri = URI.create(ipfsApiBase.toString() + "/api/v0/pubsub/pub?arg=" + PUBSUB_TOPIC);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .header("Content-Type", "application/octet-stream")
                    .build();
            httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(e -> { e.printStackTrace(); return null; });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
