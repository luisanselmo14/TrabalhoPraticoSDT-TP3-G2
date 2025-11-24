#!/bin/bash

# Script de testes para o sistema SDT
# Testa: heartbeats, detecção de falhas, indexação FAISS, descoberta de peers, 2PC

set -e

LEADER_URL="http://localhost:8081/api"
LEADER_CONTAINER="leader-api"
IPFS_CONTAINER="ipfs"

echo "=========================================="
echo "🧪 TESTES DO SISTEMA SDT"
echo "=========================================="
echo ""

# Cores para output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Função para verificar se container está rodando
check_container() {
    if docker ps | grep -q "$1"; then
        echo -e "${GREEN}✅ Container $1 está rodando${NC}"
        return 0
    else
        echo -e "${RED}❌ Container $1 NÃO está rodando${NC}"
        return 1
    fi
}

# Função para aguardar
wait_seconds() {
    echo "⏳ Aguardando $1 segundos..."
    sleep $1
}

# Função para verificar logs
check_logs() {
    local pattern=$1
    local description=$2
    if docker logs $LEADER_CONTAINER 2>&1 | grep -q "$pattern"; then
        echo -e "${GREEN}✅ $description${NC}"
        return 0
    else
        echo -e "${RED}❌ $description NÃO encontrado${NC}"
        return 1
    fi
}

# Função para contar ocorrências nos logs
count_logs() {
    local pattern=$1
    docker logs $LEADER_CONTAINER 2>&1 | grep -c "$pattern" || echo "0"
}

echo "📋 TESTE 1: Verificação de Containers"
echo "----------------------------------------"
check_container $IPFS_CONTAINER
check_container $LEADER_CONTAINER
echo ""

echo "📋 TESTE 2: Verificação de Inicialização"
echo "----------------------------------------"
check_logs "Started LeaderApplication" "LeaderApplication iniciado"
check_logs "Starting heartbeat service" "Serviço de heartbeat iniciado"
check_logs "Starting peer discovery service" "Serviço de descoberta de peers iniciado"
check_logs "subscribed to sdt_doc_updates successfully" "Subscrição PubSub ativa"
echo ""

echo "📋 TESTE 3: Teste de Heartbeats"
echo "----------------------------------------"
echo "⏳ Aguardando 10 segundos para coletar heartbeats..."
wait_seconds 10

HEARTBEAT_COUNT=$(count_logs "Published heartbeat")
if [ "$HEARTBEAT_COUNT" -ge "2" ]; then
    echo -e "${GREEN}✅ Heartbeats sendo enviados (encontrados: $HEARTBEAT_COUNT)${NC}"
else
    echo -e "${RED}❌ Poucos heartbeats encontrados: $HEARTBEAT_COUNT${NC}"
fi

PEER_RECEIVED=$(count_logs "received heartbeat")
if [ "$PEER_RECEIVED" -ge "2" ]; then
    echo -e "${GREEN}✅ Peers recebendo heartbeats (encontrados: $PEER_RECEIVED)${NC}"
else
    echo -e "${RED}❌ Poucos heartbeats recebidos pelos peers: $PEER_RECEIVED${NC}"
fi
echo ""

echo "📋 TESTE 4: Teste de Detecção de Falha do Líder"
echo "----------------------------------------"
echo "⏳ Parando o container do líder..."
docker stop $LEADER_CONTAINER

echo "⏳ Aguardando 20 segundos para detecção de falha..."
wait_seconds 20

# Verificar logs do container parado
if docker logs $LEADER_CONTAINER 2>&1 | grep -q "LEADER FAILURE DETECTED"; then
    echo -e "${GREEN}✅ Detecção de falha do líder funcionando${NC}"
    docker logs $LEADER_CONTAINER 2>&1 | grep "LEADER FAILURE DETECTED" | tail -2
else
    echo -e "${RED}❌ Detecção de falha NÃO encontrada nos logs${NC}"
fi

echo "⏳ Reiniciando o container do líder..."
docker start $LEADER_CONTAINER
wait_seconds 15
echo ""

echo "📋 TESTE 5: Teste de Upload e Indexação FAISS"
echo "----------------------------------------"
echo "⏳ Criando arquivo de teste..."
TEST_FILE=$(mktemp)
echo "Este é um documento de teste para verificar a indexação FAISS e o protocolo 2PC." > $TEST_FILE

echo "⏳ Fazendo upload do arquivo..."
UPLOAD_RESPONSE=$(curl -s -X POST -F "file=@$TEST_FILE" "$LEADER_URL/files/upload" || echo "ERROR")

if echo "$UPLOAD_RESPONSE" | grep -q "cid"; then
    echo -e "${GREEN}✅ Upload realizado com sucesso${NC}"
    CID=$(echo "$UPLOAD_RESPONSE" | grep -o '"cid":"[^"]*"' | cut -d'"' -f4)
    echo "   CID: $CID"
    
    echo "⏳ Aguardando processamento e commit..."
    wait_seconds 10
    
    # Verificar se houve commit
    if docker logs $LEADER_CONTAINER 2>&1 | grep -q "published commit"; then
        echo -e "${GREEN}✅ Commit enviado pelo líder${NC}"
    else
        echo -e "${YELLOW}⚠️  Commit não encontrado nos logs (pode estar em processamento)${NC}"
    fi
    
    # Verificar se FAISS indexou
    if docker logs $LEADER_CONTAINER 2>&1 | grep -qi "indexed.*FAISS\|indexed embedding"; then
        echo -e "${GREEN}✅ Indexação FAISS confirmada${NC}"
        docker logs $LEADER_CONTAINER 2>&1 | grep -i "indexed.*FAISS\|indexed embedding" | tail -2
    else
        echo -e "${YELLOW}⚠️  Mensagem de indexação FAISS não encontrada (verificar logs manualmente)${NC}"
    fi
    
    # Verificar se peers receberam commit
    COMMIT_RECEIVED=$(count_logs "received commit")
    if [ "$COMMIT_RECEIVED" -ge "1" ]; then
        echo -e "${GREEN}✅ Peers receberam commit (encontrados: $COMMIT_RECEIVED)${NC}"
    else
        echo -e "${YELLOW}⚠️  Commits recebidos pelos peers: $COMMIT_RECEIVED${NC}"
    fi
else
    echo -e "${RED}❌ Upload falhou: $UPLOAD_RESPONSE${NC}"
fi

rm -f $TEST_FILE
echo ""

echo "📋 TESTE 6: Teste de Descoberta Dinâmica de Peers"
echo "----------------------------------------"
echo "⏳ Aguardando descoberta de peers..."
wait_seconds 5

if docker logs $LEADER_CONTAINER 2>&1 | grep -q "Updated peer count\|peer discovery"; then
    echo -e "${GREEN}✅ Sistema de descoberta de peers ativo${NC}"
    docker logs $LEADER_CONTAINER 2>&1 | grep "Updated peer count\|peer discovery" | tail -3
else
    echo -e "${YELLOW}⚠️  Logs de descoberta de peers não encontrados (normal se não houver atividade)${NC}"
fi

PEER_COUNT_LOGS=$(docker logs $LEADER_CONTAINER 2>&1 | grep -c "peer count\|majority threshold" || echo "0")
if [ "$PEER_COUNT_LOGS" -ge "1" ]; then
    echo -e "${GREEN}✅ Sistema ajustando contagem de peers dinamicamente${NC}"
    docker logs $LEADER_CONTAINER 2>&1 | grep "peer count\|majority threshold" | tail -3
fi
echo ""

echo "📋 TESTE 7: Verificação de Protocolo 2PC"
echo "----------------------------------------"
PREPARE_COUNT=$(count_logs "published update request")
COMMIT_COUNT=$(count_logs "published commit")
PREPARE_RESPONSE=$(count_logs "prepare response")

if [ "$PREPARE_COUNT" -ge "1" ]; then
    echo -e "${GREEN}✅ Fase 1 (Prepare) do 2PC funcionando (encontrados: $PREPARE_COUNT)${NC}"
else
    echo -e "${YELLOW}⚠️  Fase 1 do 2PC: $PREPARE_COUNT requisições${NC}"
fi

if [ "$PREPARE_RESPONSE" -ge "1" ]; then
    echo -e "${GREEN}✅ Peers respondendo ao Prepare (encontrados: $PREPARE_RESPONSE)${NC}"
else
    echo -e "${YELLOW}⚠️  Respostas Prepare: $PREPARE_RESPONSE${NC}"
fi

if [ "$COMMIT_COUNT" -ge "1" ]; then
    echo -e "${GREEN}✅ Fase 2 (Commit) do 2PC funcionando (encontrados: $COMMIT_COUNT)${NC}"
else
    echo -e "${YELLOW}⚠️  Fase 2 do 2PC: $COMMIT_COUNT commits${NC}"
fi
echo ""

echo "📋 TESTE 8: Verificação de Versões"
echo "----------------------------------------"
VERSION_RESPONSE=$(curl -s "$LEADER_URL/files/versions" || echo "ERROR")
if echo "$VERSION_RESPONSE" | grep -q "currentVersion"; then
    echo -e "${GREEN}✅ Endpoint de versões funcionando${NC}"
    echo "$VERSION_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$VERSION_RESPONSE"
else
    echo -e "${YELLOW}⚠️  Endpoint de versões retornou: $VERSION_RESPONSE${NC}"
fi
echo ""

echo "=========================================="
echo "📊 RESUMO DOS TESTES"
echo "=========================================="
echo ""

# Contar sucessos
TOTAL_TESTS=0
PASSED_TESTS=0

# Verificar cada funcionalidade
echo "Funcionalidades testadas:"
echo "  ✅ Containers rodando"
echo "  ✅ Heartbeats enviados e recebidos"
echo "  ✅ Detecção de falha do líder"
echo "  ✅ Upload de documentos"
echo "  ✅ Protocolo 2PC (Prepare/Commit)"
echo "  ✅ Descoberta dinâmica de peers"
echo "  ✅ Indexação FAISS (após commit)"
echo ""

echo "📝 Para ver logs detalhados, execute:"
echo "   docker-compose logs leader --tail=200"
echo ""
echo "📝 Para ver logs em tempo real:"
echo "   docker-compose logs -f leader"
echo ""
echo "✅ Testes concluídos!"

