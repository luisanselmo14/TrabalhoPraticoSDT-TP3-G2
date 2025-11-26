# Diagrama de Sequências - Sistema de Gestão de Documentos Distribuído

```mermaid
sequenceDiagram
    %% FASE 1: INICIALIZAÇÃO
    rect rgb(173, 216, 230)
        Note over LeaderApplication,Peer: 🔵 FASE 1: INICIALIZAÇÃO
        LeaderApplication->>SpringBoot: SpringApplication.run()
        SpringBoot->>AppConfig: Configurar beans
        AppConfig->>DocumentManager: Criar DocumentManager
        DocumentManager->>EmbeddingService: Inicializar modelo
        DocumentManager->>LeaderCoordinator: Criar LeaderCoordinator
        LeaderCoordinator->>IPFS: subscribe("sdt_doc_updates")
        Peer->>IPFS: subscribe("sdt_doc_updates")
    end
    
    %% FASE 2: UPLOAD E PROCESSAMENTO
    rect rgb(144, 238, 144)
        Note over Client,DocumentManager: 🟢 FASE 2: UPLOAD E PROCESSAMENTO
        Client->>LeaderController: POST /api/files/upload
        LeaderController->>IPFS: Upload ficheiro
        IPFS-->>LeaderController: CID
        LeaderController->>DocumentManager: addDocumentAndPropagate(cid)
        DocumentManager->>EmbeddingService: generateEmbedding()
        EmbeddingService-->>DocumentManager: embedding[384]
    end
    
    %% FASE 3: 2PC - PREPARE
    rect rgb(255, 255, 153)
        Note over DocumentManager,Peer: 🟡 FASE 3: 2PC - PREPARE
        DocumentManager->>LeaderCoordinator: coordinateUpdate()
        LeaderCoordinator->>IPFS: publish("doc_update_request")
        IPFS-->>Peer: doc_update_request
        Peer->>Peer: Preparar versão + calcular hash
        Peer->>IPFS: publish("doc_update_prepare_response")
        IPFS-->>LeaderCoordinator: prepare_response
        LeaderCoordinator->>LeaderCoordinator: Verificar consenso (maioria)
    end
    
    %% FASE 4: 2PC - COMMIT
    rect rgb(255, 200, 120)
        Note over LeaderCoordinator,Peer: 🟠 FASE 4: 2PC - COMMIT
        LeaderCoordinator->>IPFS: publish("doc_update_commit")
        IPFS-->>Peer: doc_update_commit
        Peer->>Peer: Confirmar versão
        Peer->>FAISSIndex: Indexar embedding
        LeaderCoordinator-->>DocumentManager: Sucesso
        DocumentManager-->>LeaderController: version
        LeaderController-->>Client: {cid, version, status: "committed"}
    end
    
    %% FASE 5: HEARTBEAT
    rect rgb(255, 182, 193)
        Note over LeaderCoordinator,Peer: 🔴 FASE 5: HEARTBEAT
        loop A cada 5 segundos
            LeaderCoordinator->>IPFS: publish("heartbeat")
            IPFS-->>Peer: heartbeat
            Peer->>Peer: Atualizar timestamp
        end
        loop A cada 1 segundo
            Peer->>Peer: Verificar timeout (15s)
        end
    end
```

## 📋 Legenda de Fases

### 🔵 Fase 1: Inicialização
- Inicialização do Spring Boot e configuração de componentes
- Subscrição ao PubSub IPFS para comunicação distribuída

### 🟢 Fase 2: Upload e Processamento
- Upload de ficheiro para IPFS e obtenção do CID
- Geração de embeddings semânticos (384 dimensões)

### 🟡 Fase 3: 2PC - Prepare
- Líder envia pedido de atualização aos peers
- Peers preparam versão e calculam hash
- Líder verifica consenso (maioria)

### 🟠 Fase 4: 2PC - Commit
- Após consenso, líder envia commit
- Peers confirmam versão e indexam no FAISS
- Retorno de sucesso ao cliente

### 🔴 Fase 5: Heartbeat
- Líder envia heartbeats periódicos (5s)
- Peers monitoram e detetam falhas (timeout: 15s)
