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

Por padrao, o servidor tambem aceita conexoes de outros dispositivos na mesma rede Wi-Fi:

1. No Windows, execute `ipconfig` e localize o **Endereco IPv4** da conexao Wi-Fi ou Ethernet ativa.
2. No outro dispositivo conectado a mesma rede, abra `http://IP-DO-COMPUTADOR:8080`, substituindo pelo IPv4 encontrado (por exemplo, `http://<IP.DO.COMPUTADOR>:8080`).
3. Se o Windows perguntar, permita o acesso ao Java apenas em redes privadas. Se necessario, crie uma regra de entrada no Firewall do Windows para a porta TCP `8080` em redes privadas.

Isso permite acesso somente onde a rede e o firewall permitem; nao configure encaminhamento de portas no roteador para expor a loja a internet. Para alterar a porta, defina `ECOMMERCE_PORT` antes de iniciar o Maven. Para limitar novamente o servidor ao proprio computador, defina `ECOMMERCE_BIND_ADDRESS` como `127.0.0.1`.

## Usar a loja

- Crie uma conta com nome, email, CPF valido e senha de pelo menos 8 caracteres. A conta entra imediatamente.
- Entre na conta, adicione produtos a sacola e selecione **Finalizar pedido**.
- Na finalizacao, informe o telefone e o endereco completo de entrega e escolha PIX, dinheiro ou cartao. Para dinheiro, indique se precisa de troco e, se precisar, informe o valor da nota; a loja calcula o troco. Para cartao, escolha debito ou credito. A confirmacao salva esses dados junto com o pedido e os itens no H2, usa os precos atuais do banco e abate o estoque dentro de uma unica transacao.
- A sacola fica salva no navegador. Os pedidos confirmados aparecem na area da conta.
- Os pedidos tambem ficam na aba **Pedidos** do painel administrativo. O painel mostra os dados informados pelo cliente e atualiza o contador de novos pedidos periodicamente; a notificacao fica salva no banco mesmo se o administrador estiver desconectado.
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

Substitua `seu@email.com` pelo email cadastrado. O comando deve informar que uma linha foi atualizada. Inicie novamente com `mvn exec:java` e entre com o email e a senha. O botao **Painel Admin** abre a area exclusiva; use as abas **Produtos**, **Pedidos** e **Contas** para gerenciar o catalogo, acompanhar pedidos e gerenciar contas de clientes. Cadastros novos exigem nome, descricao, preco, estoque e uma foto PNG ou JPEG de ate 2 MB. Os produtos e as fotos ficam salvos no banco H2; tambem e possivel editar e excluir produtos, mas produtos incluidos em pedidos existentes nao podem ser excluidos.

Clientes tambem podem excluir a propria conta na area **Sua conta**, confirmando a senha. Para preservar a integridade do historico, os pedidos e itens ficam registrados, mas os dados pessoais da conta e os enderecos de entrega sao substituidos por valores anonimos.

## Banco de dados

O arquivo H2 persistente e criado em `db/ecommerce.mv.db`. O esquema e migracoes idempotentes ficam em [db/schema.sql](./db/schema.sql) e sao aplicados na inicializacao; pedidos antigos recebem valores padrao para os novos campos de pagamento e endereco. Para um novo catalogo vazio, feche o servidor e remova os arquivos gerados dentro de `db/`; isso tambem apaga contas, pedidos, fotos e estoque atuais.
