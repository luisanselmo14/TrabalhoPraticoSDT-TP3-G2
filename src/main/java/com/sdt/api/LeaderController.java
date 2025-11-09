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
}