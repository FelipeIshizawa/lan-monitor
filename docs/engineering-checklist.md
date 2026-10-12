# Engineering checklist

- [x] Testes de persistência e limite do spool.
- [x] Pipeline de build Compose e smoke test com MySQL real.
- [x] Smoke test cobre replay, conflito, autenticação, estados, histórico e SSE.
- [x] Publicação GHCR por tag, com SBOM e provenance.
- [x] Deploy manual em runner Linux configurado, GitHub Environment production.
- [x] Dependabot para Java, Python, frontend e Actions.
- [ ] Executar CI em GitHub e registrar o resultado.
- [ ] Fixar Actions e imagens por digest; gerar/commitar package-lock.json e trocar npm install por npm ci.
- [ ] Adicionar análise estática, scanning de secrets e vulnerabilidades ao pipeline.
- [ ] Credenciais distintas de operador/agente e autenticação por agente.
- [ ] TLS e rate limit ao expor o hub fora de localhost.
- [ ] Testes de entrada inválida, rollback completo e chegada fora de ordem em MySQL real.
- [ ] Ensaiar restauração de backup, migração e rollback de release.
- [ ] Validar ARP em VLAN de laboratório e DNS/ICMP bloqueados.
- [ ] Implementar filas limitadas por cliente SSE e medir clientes lentos.
- [ ] Benchmark de longa duração e otimização dos rollups.

## README para recrutadores

Apresentar arquitetura e decisões, GIF do dashboard, contrato de ingestão e um cenário de falha reproduzível. Publicar benchmarks com CPU/RAM/SSD, versões, intervalo de sondagem, dispositivos, lote, concorrência, duração e dataset. Distinguir meta de resultado medido. Explicar o gargalo encontrado e como foi corrigido, incluindo trade-offs de retenção e particionamento.
