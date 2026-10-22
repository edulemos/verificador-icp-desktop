# Verificador ICP-Brasil Desktop

Aplicativo Java Swing que executa localmente o motor dos Códigos de Referência LABSEC/UFSC 2.7. O projeto usa Maven e Spring Boot sem servidor web, com serviços separados da interface e operações demoradas em segundo plano.

O repositório contém os fontes, recursos, testes, scripts e documentação. JARs, executáveis, runtime portátil e dados locais são gerados ou baixados durante o uso e ficam fora do versionamento. Veja também [reader.txt](reader.txt) para instruções em texto simples.

## Executar

- Abra `iniciar.bat` na raiz do projeto, ou `release/iniciar.bat` junto ao JAR.
- Requer Java 17 ou superior para o JAR. A compilação e os testes foram realizados com JDK 21.
- Quando presente, `release/portable/VerificadorICP/VerificadorICP.exe` inclui seu próprio runtime e dispensa Java instalado. Mantenha a pasta inteira junto ao executável.

## Funcionalidades

- Interface Swing com tema Nimbus, botão de seleção no canto superior direito, suporte a arrastar um arquivo para a janela e resumo da validação abaixo.
- Validação automática em segundo plano ao escolher um documento.
- Verificação de PDF/PAdES, XML/XMLDSig/XAdES e CMS/CAdES conforme o suporte do motor.
- Associação de documento original para assinaturas destacadas pelo menu **Opções**, com nova validação automática.
- Um arquivo por vez, com indicação de processamento e seleção bloqueada até a conclusão.
- Resultado por arquivo, signatário, integridade, cifra assimétrica, cadeia de certificação, política e mensagens do motor.
- Exportação do resultado atual em PDF, HTML, XML e JSON. XML/JSON incluem o relatório técnico completo do núcleo. PDF inclui resumo do arquivo e relatórios técnicos dos documentos verificáveis; HTML apresenta um resumo legível.
- Atualização das raízes de assinatura v5, v6, v12 e v13 e do pacote oficial vigente de ACs do ITI por HTTPS, com conferência SHA-512 do pacote e preservação dos itens anteriores em caso de falha.

## Uso

1. Clique em **Selecionar arquivo…**, no canto superior direito, ou arraste um documento assinado para a janela. A validação começa automaticamente. É aceito um arquivo por vez.
2. Consulte o **Resumo da validação** logo abaixo do botão.
3. Para uma assinatura destacada, use **Opções → Vincular documento original…**. Ao escolher o original, a validação é refeita automaticamente.
4. Para guardar o resultado, use **Opções → Exportar relatório…** antes de escolher outro documento.
5. Atualização de certificados e configurações também estão no menu **Opções**.

O resultado atual fica em memória e é substituído ao selecionar outro arquivo. Os arquivos originais não são modificados nem enviados ao serviço web do ITI. O motor pode consultar serviços externos de certificados, revogação e políticas.

## Compilar e testar

```powershell
.\build.bat
```

Ou, em qualquer plataforma com Maven e JDK:

```text
mvn -s scripts/maven-settings.xml clean package
java -jar desktop/target/verificador-desktop.jar
```

O arquivo `scripts/maven-settings.xml` limita a resolução das dependências ao Maven Central, sem modificar configurações globais da máquina. O JAR executável é gerado em `desktop/target/verificador-desktop.jar`; `build.bat` também o copia para `release/`.

Para gerar o aplicativo portátil no Windows, com `jlink` e `jpackage` do JDK no PATH:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\package-portable.ps1
```

Isso gera uma pasta de aplicativo com `.exe` e runtime. Não é um instalador MSI/EXE de instalação.

## Estrutura

```text
engine/                   Núcleo Java de referência e seus modelos XSL/XSD
desktop/src/main/java/br/com/verificador/desktop/
  config/                 Caminhos locais e configuração Spring
  engine/                 Adaptador para o núcleo de referência
  model/                  Entrada, resultados e estados
  service/                Certificados e exportação
  ui/                     Janela Swing e tabela
desktop/src/test/         Assinaturas de teste geradas localmente e regressões
scripts/                  Build auxiliar e empacotamento portátil
```

O projeto é independente da pasta de origem: os fontes necessários estão em `engine/`. Não depende da instalação do `java-runner` nem de servidor Tomcat. O núcleo mantém classes web herdadas para reduzir alterações, mas o desktop não inicia nem usa seus servlets.

## Dados e certificados

No Windows, o padrão é `%LOCALAPPDATA%\VerificadorICPDesktop`, com subpastas `trust-anchors`, `intermediates`, `cache`, `repository` e `logs`. Para alterar:

```text
java -Dverificador.dataDir=C:\meus-dados\verificador -jar desktop/target/verificador-desktop.jar
```

As raízes padrão são obtidas de `https://acraiz.icpbrasil.gov.br/credenciadas/RAIZ/ICP-Brasilv{versao}.crt`, conforme o [repositório oficial da AC-Raiz](https://www.gov.br/iti/pt-br/assuntos/repositorio/repositorio-ac-raiz). O download valida a estrutura de CA raiz e a autoassinatura do certificado, além do transporte HTTPS. Não desabilita a validação TLS.

As intermediárias vêm da **Cadeia Vigente** do [pacote oficial de ACs do ITI](https://www.gov.br/iti/pt-br/assuntos/repositorio/certificados-das-acs-da-icp-brasil-arquivo-unico-compactado): `https://acraiz.icpbrasil.gov.br/credenciadas/CertificadosAC-ICP-Brasil/ACcompactado.zip`, conferido com `hashsha512.txt` do mesmo diretório. O pacote validado fica em `intermediates/iti-ac-vigente.zip`. A troca é atômica; falhas de download, hash ou conteúdo preservam o pacote anterior. O importador limita o tamanho do download, das entradas ZIP e do conteúdo descompactado e não extrai caminhos do ZIP para o disco.

O pacote fornece somente candidatos de AC intermediária para construir cadeias. Certificados de titular são rejeitados e certificados com emissor igual ao titular são excluídos da importação. Nenhum certificado do pacote é promovido a âncora de confiança. Cada assinatura ainda precisa de uma cadeia válida até uma raiz instalada, validade temporal, revogação e demais verificações do motor.

A primeira validação tenta obter o pacote caso ele não esteja instalado. **Opções → Atualizar certificados** atualiza raízes e intermediárias. Se o primeiro download das intermediárias falhar, o aplicativo tenta validar com os certificados da assinatura, o cache e AIA; a ausência de uma cadeia completa não é aprovação.

**Sobre e configurações** permite abrir as pastas. Para uso sem download, é possível instalar manualmente certificados raiz `.crt`, `.cer` ou `.pem` obtidos de fonte confiável. Esses arquivos definem o conjunto de confiança; adicionar outra raiz muda quais cadeias o aplicativo aceita. Não coloque certificados de signatários ou intermediárias nessa pasta.

## Limites e validação

- Limite de 100 MB por arquivo; verificação em memória e execução sequencial.
- Sem assinaturas verificáveis, o resultado é **Não verificado**, nunca aprovado por ausência de erros.
- Falta de dados de revogação/políticas pode produzir **Indeterminado**, mesmo com integridade criptográfica confirmada.
- O motor usa configuração global; o adaptador sincroniza chamadas entre instâncias.
- O núcleo e algumas dependências são antigos. Este aplicativo não é o serviço oficial do ITI e não promete equivalência com sua versão atual. O provedor Bouncy Castle foi migrado de 1.69 para 1.86 (`jdk18on`), com adaptações de ASN.1 e regressões para PDF, CMS, XML, cadeias e exportação. Isso não garante suporte a todos os algoritmos ou perfis ICP-Brasil.
- Os testes geram certificados, CMS anexado/destacado, PDF e XML localmente. Certificados artificiais ficam exclusivamente em diretórios temporários dos testes, sem instalar raízes de teste no aplicativo.
- Também há regressões para conteúdo adulterado, signatário não confiável, ausência de raízes, arquivos vazios/inacessíveis, ausência de assinaturas, escape de HTML, rejeição de entidades XML e exportação dos quatro formatos.

## Origem e alterações no motor

A licença original foi preservada em `engine/LICENSE`. Veja `THIRD-PARTY-NOTICES.md`. O núcleo foi copiado da versão 2.7 entregue pelo usuário. Alterações locais: adaptação das APIs ASN.1 para Bouncy Castle 1.86; leitura do CMS de PDFs removendo somente o preenchimento zero após o objeto ASN.1; correção dos limites de data na seleção de LCRs incorporadas em CMS/XML e tratamento da ausência do número opcional da LCR no relatório; coleção de certificados por instância com leitura das intermediárias a cada verificação e seleção de emissor por nome e assinatura criptográfica; logs no diretório de dados; URLs vazias de âncoras ignoradas; timeout de leitura HTTP nas âncoras; fechamento dos arquivos de âncoras e da caminhada de diretórios; rejeição de DTDs e entidades externas nos contêineres XML/XAdES; remoção da sobrescrita global de curvas desabilitadas pelo JDK. A integração desktop substitui os caminhos `/tmp/...` por configuração em memória e não utiliza o pareamento multipart do servlet antigo.

A raiz v7 utiliza Ed521, não implementado pelo provedor criptográfico desta base, e por isso não é baixada automaticamente. Assinaturas Ed521 não são suportadas nesta versão. As raízes com parâmetros EC explícitos, como v13, são lidas pelo provedor Bouncy Castle. O cliente de atualização mantém a validação TLS, usa as autoridades confiáveis do Java e do Windows e permite buscar intermediárias via AIA para completar a cadeia HTTPS do repositório oficial.

## Resumo da validação

Após a validação automática, o painel **Resumo da validação** apresenta informações gerais do arquivo e um bloco por assinatura: nome, hash SHA-256 dos bytes efetivamente verificados, data da validação, titular, CNPJ, CPF mascarado (incluindo o representante de pessoa jurídica quando disponível), número de série do certificado, emissor, data declarada da assinatura e resultado. Os relatórios técnicos continuam disponíveis na exportação PDF, XML e JSON.

Os dados de identidade são obtidos dos certificados incorporados à assinatura e associados ao signatário pelo Subject do certificado, sem alterar o parecer do motor. A data vem do atributo CMS signingTime, do SigningTime XML quando disponível ou da data declarada no PDF. Essa data não equivale a um carimbo do tempo validado. Campos ausentes aparecem como não informados. O hash refere-se ao arquivo assinado inteiro, não ao resumo criptográfico interno de uma assinatura.

O HTML exportado usa o mesmo resumo; PDF, XML e JSON também incluem os novos campos. A estrutura dos campos otherName de CPF/CNPJ segue os identificadores publicados pelo ITI no DOC-ICP-04 e no esquema de OIDs da ICP-Brasil.

## Testes do pacote de ACs

As regressões locais cobrem importação sem promover raízes, deduplicação, rejeição de certificado de titular, preservação do pacote anterior, limites ZIP, cadeias com intermediária ausente da assinatura e certificado revogado. O teste real de download é opcional e exige rede:

```powershell
mvn "-Dverificador.testOfficialCertificates=true" -s scripts/maven-settings.xml package
```

Em 22/10/2026, o teste real conferiu o SHA-512 do pacote vigente e carregou 175 intermediárias distintas, além das quatro raízes baixadas separadamente. A quantidade pode mudar com atualizações do ITI. As regressões também verificaram a integridade e a assinatura criptográfica de CMS ECDSA (P-256, P-384 e P-521), Ed25519 e Ed448. Isso não equivale a homologação desses perfis na ICP-Brasil. A raiz v7/Ed521 continua sem suporte, mesmo com a biblioteca atualizada.
