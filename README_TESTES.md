# Guia de Testes do Sistema SDT

Este documento descreve como executar os testes do sistema e verificar se todos os critérios estão sendo atendidos.

## Pré-requisitos

1. Docker e Docker Compose instalados
2. Sistema rodando: `docker-compose up -d`
3. PowerShell (Windows) ou pode adaptar para bash/Linux

## Executar Testes Automatizados

### Windows (PowerShell)
```powershell
.\test_system.ps1
```

### Linux/Mac (adaptar script)
```bash
# Converter para bash ou usar PowerShell Core
pwsh test_system.ps1
```

## Testes Incluídos

### 1. Verificação de Containers
- Verifica se IPFS e Leader estão rodando

### 2. Teste de Heartbeats
- Verifica se heartbeats estão sendo enviados a cada 5 segundos
- Verifica se peers estão recebendo heartbeats

### 3. Descoberta Dinâmica de Peers
- Verifica se o serviço de descoberta está ativo
- Verifica atualizações dinâmicas do número de peers

### 4. Detecção de Falha do Líder
- Para o líder temporariamente
- Verifica se peers detectam a falha após 15 segundos
- Reinicia o líder e verifica recuperação

### 5. Upload e Indexação FAISS
- Faz upload de um documento de teste
- Verifica se o commit foi realizado
- Verifica se a indexação FAISS foi executada

### 6. Consenso 2PC
- Verifica mensagens de prepare/commit
- Verifica se consenso foi alcançado

### 7. Descoberta Dinâmica Após Interação
- Verifica se peers foram descobertos após enviarem prepare_response

### 8. Verificação de Versões
- Verifica se o sistema retorna versões corretamente

## Verificações Manuais

### Ver Heartbeats em Tempo Real
```powershell
docker-compose logs -f leader | Select-String -Pattern "heartbeat"
```

### Ver Indexação FAISS
```powershell
docker-compose logs leader | Select-String -Pattern "FAISS|indexed"
```

### Ver Detecção de Falhas
```powershell
docker-compose logs leader | Select-String -Pattern "FAILURE|No heartbeat"
```

### Ver Descoberta de Peers
```powershell
docker-compose logs leader | Select-String -Pattern "peer discovery|Updated peer count"
```

### Ver Consenso 2PC
```powershell
docker-compose logs leader | Select-String -Pattern "consensus|prepare|commit"
```

## Critérios Verificados

✅ **Heartbeats Periódicos**
- Líder envia heartbeats a cada 5 segundos
- Peers recebem e processam heartbeats

✅ **Detecção de Falhas Fail-Stop**
- Peers detectam falha do líder após 15 segundos sem heartbeat
- Sistema registra detecção de falha

✅ **Indexação FAISS**
- Embeddings são indexados no FAISS após commit
- Índice é atualizado em memória

✅ **Descoberta Dinâmica de Peers**
- Sistema descobre peers automaticamente
- Threshold de maioria ajusta-se dinamicamente

✅ **Consenso 2PC**
- Líder envia prepare request
- Peers respondem com hash
- Líder verifica maioria e envia commit
- Peers aplicam commit e atualizam versão

## Troubleshooting

### Sistema não inicia
```powershell
docker-compose down
docker-compose up -d
docker-compose logs leader
```

### Heartbeats não aparecem
- Aguardar ~30 segundos após iniciar (tempo de carregamento do modelo)
- Verificar logs: `docker-compose logs leader --tail=100`

### FAISS não indexa
- Verificar se houve upload de documento
- Verificar logs de commit: `docker-compose logs leader | Select-String "committed"`

### Peers não são descobertos
- Verificar se peers estão enviando prepare_response
- Verificar logs: `docker-compose logs leader | Select-String "prepare response"`

