package com.sdt.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class LeaderController {
    
    private final DocumentManager documentManager;
    
    @Autowired
    public LeaderController(DocumentManager documentManager) {
        this.documentManager = documentManager;
    }
    
    @PostMapping("/upload")
    public ResponseEntity<?> uploadDocument(@RequestParam("file") MultipartFile file) {
        try {
            System.out.println("Received upload request for file: " + file.getOriginalFilename());
            
            // Salvar arquivo temporariamente
            Path tempFile = Files.createTempFile("upload-", file.getOriginalFilename());
            file.transferTo(tempFile.toFile());
            
            // Upload para IPFS
            String cid = documentManager.getIpfsClient().uploadFile(tempFile.toFile());
            System.out.println("Uploaded to IPFS: " + cid);
            
            // Adicionar documento e propagar com 2PC
            int version = documentManager.addDocumentAndPropagate(tempFile.toFile(), cid);
            
            // Limpar arquivo temporário
            Files.deleteIfExists(tempFile);
            
            return ResponseEntity.ok(Map.of(
                "cid", cid,
                "version", version,
                "status", "committed",
                "filename", file.getOriginalFilename()
            ));
            
        } catch (Exception e) {
            System.err.println("Upload failed: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    @GetMapping("/download/{cid}")
    public ResponseEntity<?> downloadDocument(@PathVariable String cid) {
        try {
            // Implementar download se necessário
            return ResponseEntity.ok(Map.of("cid", cid, "message", "Download not implemented yet"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    @GetMapping("/versions")
    public ResponseEntity<?> getVersions() {
        try {
            return ResponseEntity.ok(Map.of(
                "currentVersion", documentManager.getCurrentVersion(),
                "versions", documentManager.getVersions()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * RF2: Pesquisa de Informação - Fase 1
     * Recebe um pedido com uma prompt, gera id e token, envia para rede, devolve id ao cliente
     */
    @PostMapping("/search")
    public ResponseEntity<?> searchDocuments(@RequestBody Map<String, String> request) {
        try {
            String prompt = request.get("prompt");
            if (prompt == null || prompt.trim().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Prompt is required"));
            }
            
            System.out.println("Received search request with prompt: " + prompt);
            
            // Gerar id e token, enviar para rede
            String searchId = documentManager.initiateSearch(prompt);
            
            return ResponseEntity.ok(Map.of(
                "id", searchId,
                "status", "processing",
                "message", "Search request submitted, use GET /api/files/search/{id} to retrieve results"
            ));
            
        } catch (Exception e) {
            System.err.println("Search request failed: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * RF2: Pesquisa de Informação - Fase 2
     * Recebe o pedido do cliente para obtenção da resposta a partir do id,
     * solicita a resposta ao peer que fez o processamento,
     * se a resposta estiver disponível, devolve ao cliente
     */
    @GetMapping("/search/{id}")
    public ResponseEntity<?> getSearchResult(@PathVariable String id) {
        try {
            System.out.println("Received search result request for id: " + id);
            
            Map<String, Object> result = documentManager.getSearchResult(id);
            
            if (result == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Search result not found or still processing", "id", id));
            }
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            System.err.println("Get search result failed: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
}