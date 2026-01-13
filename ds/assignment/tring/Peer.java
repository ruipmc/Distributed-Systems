package ds.assignment.tring;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Peer {
    // Configuration
    private final String id;
    private final int port;
    private final Addr next, server;
    private String host;
    
    // State
    private final Queue<Op> queue = new ConcurrentLinkedQueue<>();
    private final Random rng = new Random();
    private volatile boolean started = false;
    private volatile long opId = 0;
    
    // Constants
    private static final int TIMEOUT_MS = 1500;
    private static final double POISSON_RATE = 4.0 / 60.0; // 4 ops/min

    public Peer(String id, int port, String nextHost, int nextPort, String srvHost, int srvPort) {
        this.id = id;
        this.port = port;
        this.next = new Addr(nextHost, nextPort);
        this.server = new Addr(srvHost, srvPort);
        this.host = getLocalHost();
    }

    // Inicia o peer, threads de geração de operações, terminal e aceita conexões para receber tokens
    public void start() throws IOException {
        System.out.printf("[%s] Started on port %d (next=%s, server=%s)\n", id, port, next, server);
        System.out.println("[" + id + "] Type 'start' to inject token");
        
        new Thread(this::generateOps, "generator").start();
        new Thread(this::readConsole, "console").start();
        
        try (ServerSocket ss = new ServerSocket(port)) {
            while (true) {
                try (Socket sock = ss.accept()) {
                    handleSocket(sock);
                } catch (Exception e) {
                    System.err.println("[" + id + "] Error: " + e.getMessage());
                }
            }
        }
    }

    // Processa uma conexão recebida, atualiza host e trata o token recebido
    private void handleSocket(Socket sock) throws Exception {
        host = sock.getLocalAddress().getHostAddress(); // Update host
        
        ObjectInputStream in = new ObjectInputStream(sock.getInputStream());
        Object obj = in.readObject();
        
        if (obj instanceof Token tok) {
            sendAck(sock);
            processToken(tok);
        }
    }

    // Envia um ACK para o peer que enviou o token
    private void sendAck(Socket sock) {
        try {
            new ObjectOutputStream(sock.getOutputStream()).writeObject(new Ack(id));
        } catch (IOException e) {
            System.err.println("[" + id + "] ACK failed: " + e.getMessage());
        }
    }

    // Processa o token recebido: executa operações da fila e encaminha o token
    private void processToken(Token tok) {
        started = true;
        tok.hops++;
        tok.ring.add(me());
        tok.edges.put(me(), next);
        tok.dead.remove(me());
        
        // Clean dead peers periodically
        if (System.currentTimeMillis() - tok.lastClean > 20_000) {
            tok.dead.clear();
            tok.lastClean = System.currentTimeMillis();
        }
        
        System.out.printf("[%s] Token (q=%d, dead=%d, hops=%d)\n", 
            id, queue.size(), tok.dead.size(), tok.hops);
        
        // Process all queued ops
        while (!queue.isEmpty()) {
            Op op = queue.poll();
            try {
                double res = callServer(op);
                System.out.printf("[%s] ✓ %s = %.2f\n", id, op, res);
            } catch (IOException e) {
                System.err.println("[" + id + "] Server error: " + e.getMessage());
                queue.offer(op); // Requeue
                break;
            }
        }
        
        forwardToken(tok);
    }

    // Encaminha o token para o próximo peer disponível, com retry e tratamento de falhas
    private void forwardToken(Token tok) {
        long backoff = 200;
        int fails = 0;
        
        while (true) {
            List<Addr> targets = getTargets(tok);
            
            // Force retry after 3 failures
            if (fails >= 3) {
                tok.dead.clear();
                fails = 0;
            }
            
            for (Addr addr : targets) {
                if (tok.dead.contains(addr)) continue;
                
                try {
                    if (sendAndWaitAck(addr, tok)) {
                        tok.dead.remove(addr);
                        System.out.println("[" + id + "] ✓ Forwarded to " + addr);
                        return;
                    } else {
                        tok.dead.add(addr);
                        System.err.println("[" + id + "] ✗ No ACK from " + addr);
                    }
                } catch (IOException e) {
                    tok.dead.add(addr);
                }
            }
            
            fails++;
            System.err.printf("[%s] All targets failed (attempt %d), retry in %dms\n", id, fails, backoff);
            sleep(backoff);
            backoff = Math.min(backoff * 2, 3000);
        }
    }

    // Envia o token para um peer e espera pelo ACK de confirmação
    private boolean sendAndWaitAck(Addr addr, Token tok) throws IOException {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(addr.host, addr.port), TIMEOUT_MS);
            sock.setSoTimeout(TIMEOUT_MS);
            
            new ObjectOutputStream(sock.getOutputStream()).writeObject(tok);
            Object resp = new ObjectInputStream(sock.getInputStream()).readObject();
            
            return resp instanceof Ack;
        } catch (Exception e) {
            return false;
        }
    }

    // Gera a lista de peers alvo para encaminhar o token, seguindo o anel e edges
    private List<Addr> getTargets(Token tok) {
        Set<Addr> targets = new LinkedHashSet<>();
        targets.add(next);
        
        // Follow edge chain
        Addr cur = next;
        for (int i = 0; i < 10 && cur != null; i++) {
            targets.add(cur);
            cur = tok.edges.get(cur);
            if (cur != null && cur.equals(me())) break;
        }
        
        targets.addAll(tok.ring);
        targets.remove(me());
        return new ArrayList<>(targets);
    }

    // Envia uma operação ao servidor de cálculo e retorna o resultado
    private double callServer(Op op) throws IOException {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(server.host, server.port), TIMEOUT_MS);
            sock.setSoTimeout(3000);
            
            PrintWriter out = new PrintWriter(sock.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            
            out.printf("%s %.2f %.2f %d %s\n", op.type, op.a, op.b, op.id, id);
            String resp = in.readLine();
            
            return Double.parseDouble(resp.split("\\s+")[1]);
        }
    }

    // Gera operações matemáticas aleatórias e adiciona à fila periodicamente
    private void generateOps() {
        while (true) {
            sleep((long)(-Math.log(1 - rng.nextDouble()) / POISSON_RATE * 1000));
            Op op = new Op(randomType(), rnd2(), rnd2(), ++opId);
            queue.offer(op);
            System.out.println("[" + id + "] Generated " + op);
        }
    }

    // Lê comandos do console, permitindo injetar o token manualmente
    private void readConsole() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                if ("start".equals(br.readLine().trim().toLowerCase())) {
                    if (!started) {
                        started = true;
                        Token tok = new Token();
                        tok.ring.add(me());
                        tok.edges.put(me(), next);
                        System.out.println("[" + id + "] Injecting token");
                        processToken(tok);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // Retorna o endereço (host, port) deste peer
    private Addr me() { return new Addr(host, port); }
    // Gera aleatoriamente o tipo de operação matemática
    private String randomType() { return new String[]{"add","sub","mul","div"}[rng.nextInt(4)]; }
    // Gera um número double aleatório com duas casas decimais
    private double rnd2() { return Math.round(rng.nextDouble() * 100 * 100) / 100.0; }
    // Pausa a thread pelo tempo especificado em ms
    private void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }
    // Retorna o endereço IP local da máquina
    private static String getLocalHost() {
        try { return InetAddress.getLocalHost().getHostAddress(); } 
        catch (Exception e) { return "127.0.0.1"; }
    }

    // === Data Classes ===
    
    record Addr(String host, int port) implements Serializable {
        @Override public String toString() { return host + ":" + port; }
    }
    
    record Op(String type, double a, double b, long id) {
        @Override public String toString() { return String.format("%s(%.2f,%.2f) [q=%d]", type, a, b, id); }
    }
    
    record Ack(String peerId) implements Serializable {}
    
    static class Token implements Serializable {
        Set<Addr> ring = new LinkedHashSet<>();    // Peers conhecidos no anel
        Map<Addr, Addr> edges = new HashMap<>();   // Mapa de quem envia para quem
        Set<Addr> dead = new HashSet<>();          // Peers que falharam
        long hops = 0;                             // Contador de voltas
        long lastClean = System.currentTimeMillis(); // Última limpeza
    }

    // Ponto de entrada: inicializa o peer com os argumentos fornecidos
    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.out.println("Usage: java Peer <id> <port> <nextHost> <nextPort> <srvHost> <srvPort>");
            System.out.println("Example: java Peer p1 5001 127.0.0.1 5002 127.0.0.1 7000");
            return;
        }
        
        new Peer(args[0], 
                 Integer.parseInt(args[1]), 
                 args[2], 
                 Integer.parseInt(args[3]), 
                 args[4], 
                 Integer.parseInt(args[5])).start();
    }
}