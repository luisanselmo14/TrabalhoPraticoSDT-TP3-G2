package com.sdt.api;


import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.nio.charset.StandardCharsets;
import java.io.PrintWriter;
import java.io.StringWriter;


@RestController
@RequestMapping("/files")
public class LeaderController {
    private final IPFSClient ipfsClient = new IPFSClient();
    private final DocumentManager docManager;

    public LeaderController() throws Exception {
        this.docManager = new DocumentManager(ipfsClient);
    }


    // @PostMapping("/upload")
    // public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
    //     try {
    //         // criar temp com suffix seguro
    //         File temp = File.createTempFile("upload-", ".tmp");
    //         file.transferTo(temp);
    //         String cid = ipfsClient.addFile(temp);

    //         Path storageRoot = Paths.get("storage");
    //         Files.createDirectories(storageRoot);
    //         Path cidDir = storageRoot.resolve(cid);
    //         Files.createDirectories(cidDir);

    //         // nome original seguro (remove path se houver)
    //         String original = file.getOriginalFilename() == null ? cid : Paths.get(file.getOriginalFilename()).getFileName().toString();
    //         Path destFile = cidDir.resolve(original);
    //         Files.move(temp.toPath(), destFile, StandardCopyOption.REPLACE_EXISTING);

    //         // grava metadata
    //         String type = file.getContentType() == null ? "" : file.getContentType();
    //         Files.writeString(cidDir.resolve(".name"), original, StandardCharsets.UTF_8);
    //         Files.writeString(cidDir.resolve(".type"), type, StandardCharsets.UTF_8);

    //         return ResponseEntity.ok("File added to IPFS with CID: " + cid);
    //     } catch (Exception e) {
    //         e.printStackTrace();
    //         StringWriter sw = new StringWriter();
    //         e.printStackTrace(new PrintWriter(sw));
    //         return ResponseEntity.internalServerError().body("Error: " + e.toString() + "\n" + sw.toString());
    //     }
    // }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            // guarda temporário e adiciona ao IPFS (já existente no teu código)
            File temp = File.createTempFile("upload-", ".tmp");
            file.transferTo(temp);
            String cid = ipfsClient.addFile(temp);

            // move para storage/<cid>/<originalName> e guarda metadata
            Path storageRoot = Paths.get("storage");
            Files.createDirectories(storageRoot);
            Path cidDir = storageRoot.resolve(cid);
            Files.createDirectories(cidDir);
            String original = file.getOriginalFilename() == null ? cid : Paths.get(file.getOriginalFilename()).getFileName().toString();
            Path destFile = cidDir.resolve(original);
            Files.move(temp.toPath(), destFile);

            // grava metadata content-type e nome
            String type = file.getContentType() == null ? "" : file.getContentType();
            Files.writeString(cidDir.resolve(".name"), original, StandardCharsets.UTF_8);
            Files.writeString(cidDir.resolve(".type"), type, StandardCharsets.UTF_8);

            // adiciona doc ao vector e propaga (gera embeddings)
            int version = docManager.addDocumentAndPropagate(destFile.toFile(), cid);

            return ResponseEntity.ok("File added to IPFS with CID: " + cid + " (version " + version + ")");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error: " + e.toString());
        }
    }

    @GetMapping("/download/{cid}")
    public ResponseEntity<byte[]> download(@PathVariable String cid) {
        try {
            Path storageRoot = Paths.get("storage");
            Path cidDir = storageRoot.resolve(cid);
            byte[] data;
            String filename = cid;
            String contentType = null;

            if (Files.exists(cidDir) && Files.isDirectory(cidDir)) {
                // ler metadata se existir
                Path namePath = cidDir.resolve(".name");
                if (Files.exists(namePath)) {
                    filename = Files.readString(namePath, StandardCharsets.UTF_8).trim();
                }
                Path typePath = cidDir.resolve(".type");
                if (Files.exists(typePath)) {
                    contentType = Files.readString(typePath, StandardCharsets.UTF_8).trim();
                    if (contentType.isBlank()) contentType = null;
                }

                Path storedFile = cidDir.resolve(filename);
                if (Files.exists(storedFile)) {
                    data = Files.readAllBytes(storedFile);
                    if (contentType == null) {
                        contentType = Files.probeContentType(storedFile);
                    }
                } else {
                    // metadata existe mas ficheiro não: buscar do IPFS e gravar com o nome original
                    data = ipfsClient.getFileBytes(cid);
                    Files.write(storedFile, data);
                    if (contentType == null) {
                        contentType = Files.probeContentType(storedFile);
                    }
                }
            } else {
                // nada local: buscar do IPFS, criar pasta e gravar com nome do cid ou metadata se disponível
                data = ipfsClient.getFileBytes(cid);
                Files.createDirectories(cidDir);
                // tentar ler metadata guardada anteriormente (fallback para cid)
                Path namePath = cidDir.resolve(".name");
                if (Files.exists(namePath)) {
                    filename = Files.readString(namePath, StandardCharsets.UTF_8).trim();
                }
                Path outFile = cidDir.resolve(filename);
                Files.write(outFile, data);
                Path typePath = cidDir.resolve(".type");
                if (Files.exists(typePath)) {
                    contentType = Files.readString(typePath, StandardCharsets.UTF_8).trim();
                } else {
                    contentType = Files.probeContentType(outFile);
                }
            }

            if (contentType == null || contentType.isBlank()) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment().filename(filename).build());
            return ResponseEntity.ok().headers(headers).body(data);
        } catch (Exception e) {
            String msg = "Error retrieving file: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            return ResponseEntity.status(500)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body(msg.getBytes(StandardCharsets.UTF_8));
        }
    }

}