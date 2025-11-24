# 🧪 Guia de Testes do Sistema SDT

Este documento descreve como executar os testes automatizados do sistema.

## 📋 Pré-requisitos

1. Docker e Docker Compose instalados
2. Containers rodando (`docker-compose up -d`)
3. Sistema totalmente inicializado (aguardar ~30-60 segundos após iniciar)

## 🚀 Executando os Testes

### Windows (PowerShell)

```powershell
.\test_system.ps1
```

### Linux/Mac (Bash)

```bash
chmod +x test_system.sh
./test_system.sh
```

## 📊 Testes Incluídos

### 1. Verificação de Containers
- ✅ Verifica se IPFS está rodando
- ✅ Verifica se Leader-API está rodando

### 2. Verificação de Inicialização
- ✅ LeaderApplication iniciado
- ✅ Serviço de heartbeat iniciado
- ✅ Serviço de descoberta de peers iniciado
- ✅ Subscrições PubSub ativas

### 3. Teste de Heartbeats
- ✅ Verifica se heartbeats estão sendo enviados (a cada 5 segundos)
- ✅ Verifica se peers estão recebendo heartbeats
- ✅ Conta número de heartbeats enviados e recebidos

### 4. Teste de Detecção de Falha do Líder
- ✅ Para o container do líder
- ✅ Aguarda 20 segundos
- ✅ Verifica se peers detectaram a falha
- ✅ Reinicia o container
- ✅ Verifica recuperação

### 5. Teste de Upload e Indexação FAISS
- ✅ Cria arquivo de teste
- ✅ Faz upload via API REST
- ✅ Verifica se commit foi enviado
- ✅ Verifica se indexação FAISS foi realizada
- ✅ Verifica se peers receberam commit

### 6. Teste de Descoberta Dinâmica de Peers
- ✅ Verifica se sistema está rastreando peers ativos
- ✅ Verifica ajuste dinâmico do threshold de maioria
- ✅ Mostra logs de descoberta

### 7. Verificação de Protocolo 2PC
- ✅ Verifica Fase 1 (Prepare) - requisições enviadas
- ✅ Verifica respostas dos peers ao Prepare
- ✅ Verifica Fase 2 (Commit) - commits enviados

### 8. Verificação de Versões
- ✅ Testa endpoint `/api/files/versions`
- ✅ Mostra versão atual e histórico

## 📝 Critérios de Aceitação Testados

### ✅ Líder
- [x] Envia commit após receber maioria de respostas com hash correto
- [x] Substitui versão atual do vetor de CIDs pela nova versão
- [x] Descoberta dinâmica de peers (desafio)

### ✅ Peer
- [x] Recebe o commit
- [x] Substitui versão atual do vetor de CIDs pela nova versão
- [x] Atualiza indexação FAISS (armazenada em memória)

### ✅ Sistema de Detecção de Falhas
- [x] Heartbeats periódicos (a cada 5 segundos)
- [x] Detecção de falha após timeout (15 segundos)
- [x] Logs de detecção de falha

## 🔍 Verificação Manual

Se quiser verificar manualmente:

### Ver logs em tempo real:
```bash
docker-compose logs -f leader
```

### Ver apenas mensagens importantes:
```bash
docker-compose logs leader 2>&1 | Select-String -Pattern "FAISS|heartbeat|FAILURE|peer discovery|commit"
```

### Fazer upload manual:
```bash
curl -X POST -F "file=@seu_arquivo.txt" http://localhost:8081/api/files/upload
```

### Ver versões:
```bash
curl http://localhost:8081/api/files/versions
```

## ⚠️ Notas

- O teste de detecção de falha para e reinicia o container do líder
- O teste de upload requer que o sistema esteja totalmente inicializado
- Alguns testes podem mostrar avisos (⚠️) se não houver atividade recente - isso é normal
- A indexação FAISS só aparece nos logs após um commit bem-sucedido

## 🐛 Troubleshooting

### Container não está rodando
```bash
docker-compose up -d
```

### Ver logs de erro
```bash
docker-compose logs leader | Select-String -Pattern "ERROR|Exception"
```

### Reiniciar tudo
```bash
docker-compose down
docker-compose up -d
```

