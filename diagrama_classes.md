# Diagrama de Classes - Sistema de Gestão de Documentos Distribuído

```mermaid
classDiagram
    %% Pacote API (Líder)
    class LeaderApplication {
        +main(String[] args)
    }
    
    class AppConfig {
        -DocumentManager documentManager
        +ipfsClient() IPFSClient
        +documentManager(IPFSClient) DocumentManager
        +cleanup()
    }
    
    class LeaderController {
        -DocumentManager documentManager
        +uploadDocument(MultipartFile) ResponseEntity
        +downloadDocument(String) ResponseEntity
        +getVersions() ResponseEntity
    }
    
    class DocumentManager {
        -Path storageRoot
        -IPFSClient ipfsClient
        -List~List~String~~ versions
        -AtomicInteger versionCounter
        -EmbeddingService embeddingService
        -LeaderCoordinator coordinator
        -ExecutorService subscriberExecutor
        +addDocumentAndPropagate(File, String) int
        +getVersions() List~List~String~~
        +getCurrentVersion() int
        +getIpfsClient() IPFSClient
        +shutdown()
        -startPubSubSubscriber()
        -applyRemoteUpdate(JsonNode)
    }
    
    class EmbeddingService {
        -ZooModel~String,float[]~ model
        -Tika tika
        -boolean modelLoaded
        +generateEmbedding(File) float[]
        +close()
        -extractText(File) String
        -generateFallbackEmbedding(File) float[]
    }
    
    class IPFSClient {
        -String ipfsApiBase
        +uploadFile(File) String
    }
    
    %% Pacote Peers
    class PeerNode {
        -String name
        -List~List~String~~ versions
        -Map~Integer,List~String~~ pendingVersions
        -Map~Integer,float[]~ pendingEmbeddings
        -Map~Integer,String~ pendingCids
        -int confirmedVersion
        -FAISSIndex faissIndex
        -AtomicLong lastHeartbeatTimestamp
        -AtomicLong lastHeartbeatSequence
        -AtomicBoolean leaderFailed
        +run()
        +getConfirmedVersion() int
        +getCurrentVector() List~String~
        +searchSimilar(float[], int) List~String~
        +getFAISSSize() int
        +shutdown()
        -startPubSubSubscriber()
        -handleUpdateRequest(JsonNode)
        -handleCommit(JsonNode)
        -handleHeartbeat(JsonNode)
        -handleRemoteUpdate(JsonNode)
        -calculateVectorHash(List~String~) String
        -publishPrepareResponse(int, String, String)
        -checkLeaderFailure()
    }
    
    class LeaderCoordinator {
        -ObjectMapper mapper
        -Map~Integer,List~String~~ prepareResponses
        -Map~Integer,CountDownLatch~ versionLatches
        -Map~String,Long~ activePeers
        -int dynamicPeerCount
        -int majorityThreshold
        -AtomicLong heartbeatSequence
        +coordinateUpdate(int, String, float[]) boolean
        +getActivePeerCount() int
        +getMajorityThreshold() int
        +shutdown()
        -startPubSubSubscriber()
        -handlePrepareResponse(JsonNode)
        -publishUpdateRequest(int, String, float[])
        -publishCommit(int)
        -publishHeartbeat()
        -updateActivePeer(String)
        -cleanupInactivePeers()
    }
    
    class FAISSIndex {
        -Map~String,float[]~ embeddings
        -Map~String,Integer~ cidVersions
        -ReadWriteLock lock
        +add(String, float[], int)
        +remove(String)
        +search(float[], int) List~String~
        +size() int
        +clear()
        +getAllCids() Set~String~
        -normalize(float[]) float[]
        -cosineSimilarity(float[], float[]) float
    }
    
    %% Relações
    LeaderApplication --> AppConfig : uses
    AppConfig --> IPFSClient : creates
    AppConfig --> DocumentManager : creates
    LeaderController --> DocumentManager : uses
    DocumentManager --> IPFSClient : uses
    DocumentManager --> EmbeddingService : uses
    DocumentManager --> LeaderCoordinator : uses
    PeerNode --> FAISSIndex : uses
    LeaderCoordinator ..> PeerNode : coordinates via PubSub
    DocumentManager ..> PeerNode : communicates via PubSub
```

## 📋 Descrição das Classes

### Pacote `com.sdt.api` (Líder)

- **LeaderApplication**: Ponto de entrada da aplicação Spring Boot
- **AppConfig**: Configuração Spring que cria e injeta beans
- **LeaderController**: API REST para gestão de documentos
- **DocumentManager**: Gerencia documentos, versões e coordena atualizações
- **EmbeddingService**: Gera embeddings semânticos usando modelos ML
- **IPFSClient**: Cliente para interação com IPFS

### Pacote `com.sdt.peers` (Peers)

- **PeerNode**: Nó peer que mantém estado distribuído e processa atualizações
- **LeaderCoordinator**: Coordena protocolo 2PC e envia heartbeats
- **FAISSIndex**: Índice vetorial para busca por similaridade

## 🔗 Relações Principais

- **Composição**: `DocumentManager` contém `EmbeddingService` e `LeaderCoordinator`
- **Agregação**: `PeerNode` contém `FAISSIndex`
- **Dependência**: `LeaderController` depende de `DocumentManager`
- **Comunicação**: `LeaderCoordinator` e `PeerNode` comunicam via PubSub IPFS

