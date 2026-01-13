package ds.assignment.p2p;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Peer {
    private final String peerId;
    private final int port;
    private double value;

    // Map: peerId -> "host:port" (ex: "p2" -> "127.0.0.1:5002")
    private final Map<String, String> neighbors;
    private final Random random;
    private volatile boolean running;

    private int syncCount = 0;

    // Para bootstrap robusto
    private static final long REGISTER_RETRY_MS = 500;
    private static final long BOOTSTRAP_DELAY_MS = 1200;

    public Peer(String peerId, int port, Double initialValue) {
        this.peerId = peerId;
        this.port = port;
        this.neighbors = new ConcurrentHashMap<>();
        this.random = new Random();
        this.running = true;

        // Se não der valor inicial, gera random em (0,1)
        if (initialValue == null) {
            do {
                this.value = random.nextDouble();
            } while (this.value == 0.0 || this.value == 1.0);
        } else {
            this.value = initialValue;
        }
    }

    // Inicia o peer, threads de listener, sincronização e status
    public void start() {
        System.out.printf("[%s] Iniciado na porta %d com valor inicial %.6f%n",
                peerId, port, value);

        // Thread para aceitar registos de outros peers
        new Thread(this::acceptConnections, peerId + "-Listener").start();

        // Thread para sincronizar (Anti-Entropy, Poisson 2/min)
        new Thread(this::periodicSync, peerId + "-Syncer").start();

        // Thread para mostrar status
        new Thread(this::showStatus, peerId + "-Status").start();
    }

    
    // Aceita conexões de outros peers para registo e sincronização
    private void acceptConnections() {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.printf("[%s] A aguardar conexões na porta %d...%n", peerId, port);

            while (running) {
                try {
                    Socket socket = server.accept();
                    // Processar cada conexão numa thread separada
                    new Thread(() -> handleConnection(socket), peerId + "-Conn").start();
                } catch (Exception e) {
                    if (running) {
                        System.err.printf("[%s] Erro ao aceitar conexão: %s%n",
                                peerId, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.printf("[%s] Erro no servidor: %s%n", peerId, e.getMessage());
        }
    }

    // Processa uma conexão recebida, tratando comandos REGISTER e SYNC
    private void handleConnection(Socket socket) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            String message = in.readLine();
            if (message == null) return;

            String[] parts = message.split(" ");
            String command = parts[0];

            if ("REGISTER".equals(command) && parts.length == 3) {
                // REGISTER <peerId> <host:port>
                String remotePeerId = parts[1];
                String remoteAddress = parts[2];

                if (!remotePeerId.equals(this.peerId)) {
                    neighbors.put(remotePeerId, remoteAddress);
                    System.out.printf("[%s] Peer %s registado em %s (Total vizinhos: %d)%n",
                            peerId, remotePeerId, remoteAddress, neighbors.size());
                }
                out.println("OK");
                return;
            }

            if ("SYNC".equals(command) && parts.length == 3) {
                // SYNC <peerId> <value>
                String remotePeerId = parts[1];
                double remoteValue = Double.parseDouble(parts[2]);

                double oldValue;
                synchronized (this) {
                    oldValue = value;
                    value = (value + remoteValue) / 2.0;
                    syncCount++;
                }

                System.out.printf("[%s] RECV sync de %s: %.6f + %.6f → %.6f%n",
                        peerId, remotePeerId, oldValue, remoteValue, value);

                // Responder com o valor ANTIGO (antes da atualização)
                out.println(oldValue);
                return;
            }

            // desconhecido
            out.println("ERR");

        } catch (Exception e) {
            System.err.printf("[%s] Erro ao processar conexão: %s%n",
                    peerId, e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }


    // Regista este peer com outro peer (uma tentativa).

    // Tenta registrar este peer com outro peer uma vez
    public boolean registerWith(String targetHost, int targetPort) {
        try (Socket socket = new Socket(targetHost, targetPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()))) {

            String myHostForOther = advertisedHostFor(targetHost);
            String myAddress = myHostForOther + ":" + port;

            out.println("REGISTER " + peerId + " " + myAddress);

            String response = in.readLine();
            if ("OK".equals(response)) {
                System.out.printf("[%s] Registado com sucesso em %s:%d%n",
                        peerId, targetHost, targetPort);
                return true;
            }

        } catch (IOException e) {
            System.err.printf("[%s] Falha ao registar com %s:%d - %s%n",
                    peerId, targetHost, targetPort, e.getMessage());
        }
        return false;
    }


     // Registo com retry automático (para poderes arrancar peers em qualquer ordem).

    // Tenta registrar com outro peer repetidamente até sucesso
    public void registerWithRetry(String targetHost, int targetPort) {
        new Thread(() -> {
            while (running) {
                boolean ok = registerWith(targetHost, targetPort);
                if (ok) return;
                sleep(REGISTER_RETRY_MS);
            }
        }, peerId + "-RegRetry-" + targetPort).start();
    }

    
    // Retorna o IP a ser anunciado para o peer alvo (localhost ou IP real)
    private String advertisedHostFor(String targetHost) {
        String th = targetHost.trim().toLowerCase(Locale.ROOT);
        if (th.equals("127.0.0.1") || th.equals("localhost")) {
            return "127.0.0.1";
        }
        return getLocalHost();
    }

    

    // Sync com Poisson com frequência λ = 2 syncs/minuto = 2/60 syncs/segundo
    private void periodicSync() {
        double lambda = 2.0 / 60.0; // 2 syncs por minuto

        // Esperar para a rede se formar (bootstrap + retries)
        sleep(3000);

        while (running) {
            // Tempo entre syncs segue distribuição exponencial
            double u = random.nextDouble();
            long waitMs = (long) (-Math.log(1 - u) / lambda * 1000);
            if (waitMs < 1) waitMs = 1;
            sleep(waitMs);

            if (!neighbors.isEmpty()) {
                syncWithRandomNeighbor();
            }
        }
    }

    // Sincroniza o valor com um vizinho aleatório
    private void syncWithRandomNeighbor() {
        List<Map.Entry<String, String>> list = new ArrayList<>(neighbors.entrySet());
        Map.Entry<String, String> selected = list.get(random.nextInt(list.size()));

        String targetPeerId = selected.getKey();
        String targetAddress = selected.getValue();
        String[] parts = targetAddress.split(":");
        String host = parts[0];
        int targetPort = Integer.parseInt(parts[1]);

        try (Socket socket = new Socket(host, targetPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()))) {

            double myValue;
            synchronized (this) {
                myValue = value;
            }

            // Enviar SYNC com o meu valor atual
            out.println("SYNC " + peerId + " " + myValue);

            // Receber valor ANTIGO do outro peer
            String response = in.readLine();
            if (response == null) return;

            double remoteOldValue = Double.parseDouble(response);

            synchronized (this) {
                double oldValue = value;
                value = (myValue + remoteOldValue) / 2.0;
                syncCount++;

                System.out.printf("[%s] SEND sync para %s: %.6f + %.6f → %.6f%n",
                        peerId, targetPeerId, oldValue, remoteOldValue, value);
            }

        } catch (Exception e) {
            // Vizinho pode estar em baixo; ignora
        }
    }


    // Mostra periodicamente o status do peer (valor, vizinhos, syncs)
    private void showStatus() {
        sleep(5000);
        while (running) {
            synchronized (this) {
                System.out.printf("[%s] Valor: %.6f | Vizinhos: %d | Syncs: %d%n",
                        peerId, value, neighbors.size(), syncCount);
            }
            sleep(10000);
        }
    }

    // Pausa a thread pelo tempo especificado em ms
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Retorna o endereço IP local da máquina
    private String getLocalHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    // Para a execução do peer e mostra o valor final
    public void stop() {
        running = false;
        System.out.printf("[%s] A parar. Valor final: %.6f%n", peerId, value);
    }

    // Retorna o valor atual do peer
    public double getValue() {
        synchronized (this) {
            return value;
        }
    }

    // Retorna o número de vizinhos registrados
    public int getNeighborCount() {
        return neighbors.size();
    }

    // Retorna o número de sincronizações realizadas
    public int getSyncCount() {
        synchronized (this) {
            return syncCount;
        }
    }

// }
    // ===== MAIN com bootstrap automático da topologia da figura =====
    // Ponto de entrada: inicializa o peer e realiza o bootstrap da topologia
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java ds.assignment.p2p.Peer <peerId> <port> [initialValue]");
            System.exit(1);
        }

        String peerId = args[0];
        int port = Integer.parseInt(args[1]);

        Double initialValue = null;
        if (args.length >= 3) {
            initialValue = Double.parseDouble(args[2]);
        }

        Peer peer = new Peer(peerId, port, initialValue);

        // Arranca threads (listener + anti-entropy + status)
        peer.start();

        // Dá um pouco de tempo para os peers abrirem a porta, mas não depende disto
        Thread.sleep(BOOTSTRAP_DELAY_MS);

        switch (peerId) {
            case "p1":
                peer.registerWithRetry("127.0.0.1", 5002);
                break;

            case "p2":
                peer.registerWithRetry("127.0.0.1", 5001);
                peer.registerWithRetry("127.0.0.1", 5003);
                peer.registerWithRetry("127.0.0.1", 5004);
                break;

            case "p3":
                peer.registerWithRetry("127.0.0.1", 5002);
                break;

            case "p4":
                peer.registerWithRetry("127.0.0.1", 5002);
                peer.registerWithRetry("127.0.0.1", 5005);
                peer.registerWithRetry("127.0.0.1", 5006);
                break;

            case "p5":
                peer.registerWithRetry("127.0.0.1", 5004);
                break;

            case "p6":
                peer.registerWithRetry("127.0.0.1", 5004);
                break;

            default:
                System.err.println("Unknown peerId: " + peerId + " (expected p1..p6)");
        }

        System.out.println("[" + peerId + "] bootstrap started (with retry).");
    }
}
