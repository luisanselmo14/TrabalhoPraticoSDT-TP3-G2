
# Projeto SDT TP3 G2

Uma breve descrição sobre o que esse projeto faz e para quem ele é


## Deploy

Para fazer o deploy desse projeto rode

```pwsh
  docker compose up --build
```

Para reiniciar o projeto rode
```pwsh
  docker compose down
  docker image prune -f
  docker compose up --build
```


## Rodando os testes

Os comandos de teste são os seguintes:

Upload de ficheiros
```bash
    curl -X POST -F "file=@C:\caminho\do\ficheiro\teste.pdf" http://localhost:8081/api/files/upload
```

Download de ficheiros
```bash
    curl -L -J -O http://localhost:8081/api/files/download/<CID do Ficheiro>
```