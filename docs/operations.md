# Operação

## Backup e restauração

Executar no host com `.env` configurado. O dump usa transação consistente para tabelas InnoDB. Evitar concorrência com manutenção de partições (DDL); pausar backend e agente antes do backup quando necessário. Tratar backups como dados sensíveis e armazenar cópia fora do host.

```sh
docker compose stop agent backend
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u monitor --single-transaction --no-tablespaces lan_monitor' > backup.sql
docker compose start backend agent
```

Restaurar somente em banco vazio de ambiente separado; o dump inclui a tabela de histórico Flyway.

```sh
docker compose stop agent backend
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u monitor lan_monitor' < backup.sql
docker compose start backend agent
```

## Deploy

O workflow manual precisa de runner self-hosted Linux com Docker Compose, Python 3, label `lan-monitor` e secrets `DB_PASSWORD`, `DB_ROOT_PASSWORD`, `AGENT_TOKEN` no environment `production`. Restringir branches/tags autorizados no environment. Não usar esse runner em workflows de PR não confiáveis. O workflow sobe o modo sintético; LAN real exige o override Linux e configuração de interface no host.

Na versão atual, deploy recompila do commit selecionado e publica imagens separadamente no GHCR. Antes de produção, migrar o deploy para digests das imagens publicadas e testar rollback. Para rollback manual: checkout da release anterior e `docker compose -p lan-monitor up -d --build`. Isso não reverte migrations: mudanças no schema devem ser compatíveis com a versão anterior.

As senhas de inicialização do MySQL só são aplicadas com volume novo. Alterar `.env` não rotaciona usuários de um volume existente. `docker compose down -v` elimina o banco e o spool; usar apenas para laboratório descartável.
