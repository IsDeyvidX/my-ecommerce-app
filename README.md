# Fabregas Variedades

Loja local em Java com interface azul e preta, banco H2 persistente, cadastro de clientes, gerenciamento de catalogo e pedidos com baixa atomica do estoque.

## Requisitos

- Java 17
- Maven 3.9 ou mais recente

## Executar

Na pasta do projeto:

```powershell
mvn clean package
mvn exec:java
```

Abra `http://localhost:8080` no navegador. Mantenha o terminal aberto enquanto usa a loja; `Ctrl+C` encerra o servidor.

## Usar a loja

- Configure o envio de email abaixo antes de criar uma conta ou entrar. Clientes e administradores precisam confirmar um codigo de seis digitos enviado por email a cada login; o codigo expira em 10 minutos e permite ate cinco tentativas.
- Crie uma conta com nome, email, CPF valido e senha de pelo menos 8 caracteres. A conta so entra depois que confirmar o codigo enviado para o email cadastrado.
- Entre na conta, adicione produtos a sacola e selecione **Finalizar pedido**.
- Na finalizacao, informe o endereco completo de entrega e escolha PIX, dinheiro ou cartao. A confirmacao salva esses dados junto com o pedido e os itens no H2, usa os precos atuais do banco e abate o estoque dentro de uma unica transacao.
- A sacola fica salva no navegador. Os pedidos confirmados aparecem na area da conta.
- A forma de pagamento e apenas registrada como preferencia; este prototipo nao cobra pagamentos nem integra servicos externos.

As senhas novas sao armazenadas com hash PBKDF2-HMAC-SHA256 e salt aleatorio. Senhas em texto simples deixadas pela versao anterior do prototipo sao convertidas para hash quando a conta correspondente entrar pela primeira vez.

## Administracao

Uma conta nova nunca recebe permissao administrativa pelo formulario. Para conceder acesso:

1. Crie sua conta na loja e anote o email.
2. Encerre o servidor com `Ctrl+C`.
3. Na pasta do projeto, atualize o papel dessa conta no H2:

```powershell
$h2 = "$env:USERPROFILE\.m2\repository\com\h2database\h2\2.2.224\h2-2.2.224.jar"
java -cp $h2 org.h2.tools.Shell -url "jdbc:h2:file:./db/ecommerce" -user sa -sql "UPDATE users SET role = 'ADMIN' WHERE email = 'seu@email.com';"
```

Substitua `seu@email.com` pelo email cadastrado. O comando deve informar que uma linha foi atualizada. Inicie novamente com `mvn exec:java`, entre na conta e confirme o codigo recebido por email. O botao **Painel Admin** abre a area exclusiva de administracao, diretamente no formulario para adicionar produtos independentes do codigo-fonte. Cadastros novos exigem nome, descricao, preco, estoque e uma foto PNG ou JPEG de ate 2 MB. Os produtos e as fotos ficam salvos no banco H2; tambem e possivel editar e excluir produtos, mas produtos incluidos em pedidos existentes nao podem ser excluidos.

## Envio de codigos por Gmail

O envio usa `smtp.gmail.com` com STARTTLS. Ative a verificacao em duas etapas na conta Google e crie uma **senha de app** para o aplicativo; a senha normal da conta Google nao funciona. Configure estas variaveis de ambiente no PowerShell **antes** de iniciar o Maven. Nunca salve a senha de app no codigo ou no repositorio:

```powershell
$env:ECOMMERCE_SMTP_USERNAME = "sua-conta@gmail.com"
$env:ECOMMERCE_SMTP_APP_PASSWORD = "sua-senha-de-app"
$env:ECOMMERCE_SMTP_FROM = "sua-conta@gmail.com"
mvn exec:java
```

`ECOMMERCE_SMTP_FROM` e opcional e, se omitida, usa `ECOMMERCE_SMTP_USERNAME`. As variaveis acima valem apenas para a janela atual do PowerShell; configure-as novamente em cada nova sessao.

## Banco de dados

O arquivo H2 persistente e criado em `db/ecommerce.mv.db`. O esquema e migracoes idempotentes ficam em [db/schema.sql](./db/schema.sql) e sao aplicados na inicializacao; pedidos antigos recebem valores padrao para os novos campos de pagamento e endereco. Para um novo catalogo vazio, feche o servidor e remova os arquivos gerados dentro de `db/`; isso tambem apaga contas, pedidos, fotos e estoque atuais.
