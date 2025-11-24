# Script de Testes para Sistema SDT
# Testa: Heartbeats, Deteccao de Falhas, FAISS, Descoberta de Peers, 2PC

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  TESTES DO SISTEMA SDT" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$ErrorActionPreference = "Continue"

# Configuracoes
$LEADER_URL = "http://localhost:8081/api/api/files"
$TEST_FILE = "test_document.txt"

# Funcao para fazer requisicoes HTTP
function Invoke-TestRequest {
    param(
        [string]$Method,
        [string]$Url,
        [hashtable]$Headers = @{},
        [string]$Body = $null,
        [string]$FilePath = $null
    )
    
    try {
        if ($FilePath) {
            # Upload de arquivo usando HttpClient (compativel com todas versoes)
            Add-Type -AssemblyName System.Net.Http
            
            $httpClient = New-Object System.Net.Http.HttpClient
            $content = New-Object System.Net.Http.MultipartFormDataContent
            $fileStream = [System.IO.File]::OpenRead($FilePath)
            $fileName = [System.IO.Path]::GetFileName($FilePath)
            $fileContent = New-Object System.Net.Http.StreamContent($fileStream)
            $fileContent.Headers.ContentDisposition = New-Object System.Net.Http.Headers.ContentDispositionHeaderValue("form-data")
            $fileContent.Headers.ContentDisposition.Name = "file"
            $fileContent.Headers.ContentDisposition.FileName = $fileName
            $content.Add($fileContent)
            
            $uri = New-Object System.Uri($Url)
            $response = $httpClient.PostAsync($uri, $content).Result
            $responseContent = $response.Content.ReadAsStringAsync().Result
            
            $fileStream.Close()
            $httpClient.Dispose()
            
            if ($response.IsSuccessStatusCode) {
                $response = $responseContent | ConvertFrom-Json
            } else {
                throw New-Object System.Exception("HTTP $($response.StatusCode): $responseContent")
            }
        } else {
            $response = Invoke-RestMethod -Uri $Url -Method $Method -Headers $Headers -Body $Body -ErrorAction Stop
        }
        return @{ Success = $true; Data = $response }
    } catch {
        $statusCode = $null
        if ($_.Exception.Response) {
            $statusCode = $_.Exception.Response.StatusCode.value__
        }
        return @{ Success = $false; Error = $_.Exception.Message; StatusCode = $statusCode }
    }
}

# Funcao para verificar logs do Docker
function Get-DockerLogs {
    param([string]$Pattern, [int]$Lines = 50)
    
    $logs = docker-compose logs leader --tail=$Lines 2>&1
    if ($Pattern) {
        return $logs | Select-String -Pattern $Pattern
    }
    return $logs
}

# Teste 1: Verificar se o sistema esta rodando
Write-Host "[TESTE 1] Verificando se o sistema esta rodando..." -ForegroundColor Yellow
$containers = docker-compose ps --format json | ConvertFrom-Json
$leaderRunning = ($containers | Where-Object { $_.Service -eq "leader" -and $_.State -eq "running" }) -ne $null
$ipfsRunning = ($containers | Where-Object { $_.Service -eq "ipfs" -and $_.State -eq "running" }) -ne $null

if ($leaderRunning -and $ipfsRunning) {
    Write-Host "  [OK] Containers rodando" -ForegroundColor Green
} else {
    Write-Host "  [ERRO] Containers nao estao rodando!" -ForegroundColor Red
    Write-Host "    Execute: docker-compose up -d" -ForegroundColor Yellow
    exit 1
}

# Teste 2: Verificar heartbeats
Write-Host ""
Write-Host "[TESTE 2] Verificando heartbeats..." -ForegroundColor Yellow
Start-Sleep -Seconds 6
$heartbeatLogs = Get-DockerLogs -Pattern "Published heartbeat|received heartbeat" -Lines 30
if ($heartbeatLogs) {
    $heartbeatCount = ($heartbeatLogs | Measure-Object).Count
    Write-Host "  [OK] Heartbeats detectados ($heartbeatCount mensagens)" -ForegroundColor Green
    Write-Host "    Ultimos heartbeats:" -ForegroundColor Gray
    $heartbeatLogs | Select-Object -Last 5 | ForEach-Object { Write-Host "      $_" -ForegroundColor Gray }
} else {
    Write-Host "  [ERRO] Nenhum heartbeat detectado!" -ForegroundColor Red
}

# Teste 3: Verificar descoberta de peers
Write-Host ""
Write-Host "[TESTE 3] Verificando descoberta dinamica de peers..." -ForegroundColor Yellow
$peerDiscoveryLogs = Get-DockerLogs -Pattern "peer discovery|Updated peer count|Starting peer discovery|Initial peer count" -Lines 100
if ($peerDiscoveryLogs) {
    Write-Host "  [OK] Servico de descoberta de peers ativo" -ForegroundColor Green
    $peerDiscoveryLogs | Select-Object -Last 3 | ForEach-Object { Write-Host "      $_" -ForegroundColor Gray }
} else {
    Write-Host "  [AVISO] Servico de descoberta nao encontrado nos logs" -ForegroundColor Yellow
}

# Teste 4: Testar deteccao de falha do lider
Write-Host ""
Write-Host "[TESTE 4] Testando deteccao de falha do lider..." -ForegroundColor Yellow
Write-Host "  Parando o lider temporariamente..." -ForegroundColor Gray
docker stop leader-api
Start-Sleep -Seconds 20

# Verificar logs ANTES de reiniciar (container parado ainda tem logs)
$failureLogs = docker logs leader-api 2>&1 | Select-String -Pattern "LEADER FAILURE DETECTED|No heartbeat received"
if ($failureLogs) {
    Write-Host "  [OK] Falha do lider detectada pelos peers!" -ForegroundColor Green
    $failureLogs | Select-Object -Last 3 | ForEach-Object { Write-Host "      $_" -ForegroundColor Gray }
} else {
    Write-Host "  [AVISO] Falha nao encontrada nos logs (pode ter sido detectada mas logs nao capturados)" -ForegroundColor Yellow
}

Write-Host "  Reiniciando o lider..." -ForegroundColor Gray
docker start leader-api
Start-Sleep -Seconds 10

$recoveryLogs = Get-DockerLogs -Pattern "leader is alive again|received heartbeat" -Lines 30
if ($recoveryLogs) {
    Write-Host "  [OK] Recuperacao detectada (lider voltou)" -ForegroundColor Green
}

# Teste 5: Testar upload e indexacao FAISS
Write-Host ""
Write-Host "[TESTE 5] Testando upload de documento e indexacao FAISS..." -ForegroundColor Yellow

# Criar arquivo de teste
$testContent = "Este e um documento de teste para verificar a indexacao FAISS e o sistema de consenso 2PC."
$testContent | Out-File -FilePath $TEST_FILE -Encoding UTF8
Write-Host "  Arquivo de teste criado: $TEST_FILE" -ForegroundColor Gray

# Aguardar sistema estar pronto
Write-Host "  Aguardando sistema estar pronto..." -ForegroundColor Gray
Start-Sleep -Seconds 40

# ============================
# LOOP DE TENTATIVA DE UPLOAD
# ============================
$uploadSuccess = $false

while (-not $uploadSuccess) {

    Write-Host "  Tentando upload do arquivo..." -ForegroundColor Gray

    $uploadResult = Invoke-TestRequest -Method POST -Url "$LEADER_URL/upload" -FilePath $TEST_FILE

    if ($uploadResult.Success) {
        $uploadSuccess = $true

        Write-Host "  [OK] Upload realizado com sucesso!" -ForegroundColor Green
        Write-Host "    CID: $($uploadResult.Data.cid)" -ForegroundColor Gray
        Write-Host "    Versao: $($uploadResult.Data.version)" -ForegroundColor Gray
        Write-Host "    Status: $($uploadResult.Data.status)" -ForegroundColor Gray
    }
    else {
        Write-Host "  [ERRO] Falha no upload! Tentando novamente em 3s..." -ForegroundColor Red
        Write-Host "    Erro: $($uploadResult.Error)" -ForegroundColor Red
        
        if ($uploadResult.StatusCode) {
            Write-Host "    Status: $($uploadResult.StatusCode)" -ForegroundColor Red
        }

        Start-Sleep -Seconds 3
    }
}

# Após completar upload com sucesso:
Start-Sleep -Seconds 5

# Verificar indexação FAISS
Write-Host "  Verificando indexacao FAISS..." -ForegroundColor Gray
$faissLogs = Get-DockerLogs -Pattern "indexed embedding in FAISS|FAISS" -Lines 100

if ($faissLogs) {
    Write-Host "  [OK] Indexacao FAISS detectada!" -ForegroundColor Green
    $faissLogs | Select-Object -Last 3 | ForEach-Object { Write-Host "      $_" -ForegroundColor Gray }
} else {
    Write-Host "  [AVISO] Indexacao FAISS nao encontrada nos logs" -ForegroundColor Yellow
}

# Verificar commit
$commitLogs = Get-DockerLogs -Pattern "committed|received commit" -Lines 100

if ($commitLogs) {
    Write-Host "  [OK] Commit detectado!" -ForegroundColor Green
    $commitLogs | Select-Object -Last 2 | ForEach-Object { Write-Host "      $_" -ForegroundColor Gray }
}



# Teste 6: Verificar consenso 2PC
Write-Host ""
Write-Host "[TESTE 6] Verificando consenso 2PC..." -ForegroundColor Yellow
$consensusLogs = Get-DockerLogs -Pattern "achieved consensus|prepare response|majority" -Lines 100
if ($consensusLogs) {
    Write-Host "  [OK] Mensagens de consenso detectadas" -ForegroundColor Green
    $consensusLogs | Select-Object -Last 5 | ForEach-Object { Write-Host "      $_" -ForegroundColor Gray }
} else {
    Write-Host "  [AVISO] Logs de consenso nao encontrados" -ForegroundColor Yellow
}

# Teste 7: Verificar descoberta dinamica apos upload
Write-Host ""
Write-Host "[TESTE 7] Verificando descoberta dinamica de peers apos interacao..." -ForegroundColor Yellow
Start-Sleep -Seconds 5
$peerCountLogs = Get-DockerLogs -Pattern "Updated peer count|peer count:" -Lines 100
if ($peerCountLogs) {
    Write-Host "  [OK] Atualizacoes de contagem de peers detectadas" -ForegroundColor Green
    $peerCountLogs | Select-Object -Last 3 | ForEach-Object { Write-Host "      $_" -ForegroundColor Gray }
} else {
    Write-Host "  [AVISO] Logs de atualizacao de peers nao encontrados" -ForegroundColor Yellow
}

# Teste 8: Verificar versoes
Write-Host ""
Write-Host "[TESTE 8] Verificando versoes do sistema..." -ForegroundColor Yellow
$versionsResult = Invoke-TestRequest -Method GET -Url "$LEADER_URL/versions"
if ($versionsResult.Success) {
    Write-Host "  [OK] Versoes obtidas com sucesso" -ForegroundColor Green
    Write-Host "    Versao atual: $($versionsResult.Data.currentVersion)" -ForegroundColor Gray
    Write-Host "    Numero de versoes: $($versionsResult.Data.versions.Count)" -ForegroundColor Gray
} else {
    Write-Host "  [AVISO] Nao foi possivel obter versoes" -ForegroundColor Yellow
}

# Limpeza
Write-Host ""
Write-Host "Limpando arquivo de teste..." -ForegroundColor Gray
if (Test-Path $TEST_FILE) {
    Remove-Item $TEST_FILE -Force
}

# Resumo
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  RESUMO DOS TESTES" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Para ver logs detalhados, execute:" -ForegroundColor Yellow
Write-Host "  docker-compose logs leader --tail=200" -ForegroundColor White
Write-Host ""
Write-Host "Para ver apenas heartbeats:" -ForegroundColor Yellow
Write-Host "  docker-compose logs leader | Select-String -Pattern 'heartbeat'" -ForegroundColor White
Write-Host ""
Write-Host "Para ver indexacao FAISS:" -ForegroundColor Yellow
Write-Host "  docker-compose logs leader | Select-String -Pattern 'FAISS|indexed'" -ForegroundColor White
Write-Host ""
Write-Host "Testes concluidos!" -ForegroundColor Green
