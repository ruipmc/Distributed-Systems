package ds.assignment.tom;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public class Peer {

    // ------------ Peer config ------------
    public static class PeerInfo {
        public final int pid;
        public final String host;
        public final int port;
        public PeerInfo(int pid, String host, int port) {
            this.pid = pid; this.host = host; this.port = port;
        }
    }

    // ------------ Runtime state ------------
    private final int myPid; // 0..5
    private final Map<Integer, PeerInfo> peers;           // pid -> info
    private final int numPeers;
    private final PeerInfo me;

    // Lamport clock
    private final AtomicLong lamport = new AtomicLong(0);

    // lastSeenLamport[pid] = max Lamport timestamp we've seen in any packet from pid
    private final AtomicLong[] lastSeenLamport;

    // Holdback queue for DATA messages only
    private final PriorityBlockingQueue<Event> holdback = new PriorityBlockingQueue<>();

    // Deduplication by msgId (DATA only)
    private final Set<String> seenData = ConcurrentHashMap.newKeySet();

    // Dictionary words
    private final List<String> dictionaryWords;

    // Networking
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    // Threads
    private Thread listenerThread;
    private Thread processThread;
    private Thread generatorThread;

    public Peer(int myPid, String ipTablePath, String dictionaryPath) throws IOException {
        this.myPid = myPid;
        this.peers = loadPeers(ipTablePath);
        if (!peers.containsKey(myPid)) {
            throw new IllegalArgumentException("myPid " + myPid + " not found in ip_table");
        }
        this.numPeers = peers.size();
        this.me = peers.get(myPid);

        this.lastSeenLamport = new AtomicLong[numPeers];
        for (int i = 0; i < numPeers; i++) {
            this.lastSeenLamport[i] = new AtomicLong(0);
        }

        this.dictionaryWords = loadDictionary(dictionaryPath);
        if (dictionaryWords.isEmpty()) {
            throw new IllegalArgumentException("Dictionary has no usable words: " + dictionaryPath);
        }
    }

    // ----------------- Public API -----------------
    // Inicia o peer, threads de listener, processamento e geração de mensagens
    public void start() throws IOException {
        System.out.println("Starting PID=" + (myPid + 1) + " on " + me.host + ":" + me.port
                + " peers=" + numPeers + " dictWords=" + dictionaryWords.size());

        serverSocket = new ServerSocket(me.port, 50, InetAddress.getByName(me.host));

        listenerThread = new Thread(this::listenLoop, "listener-" + (myPid + 1));
        processThread = new Thread(this::processLoop, "process-" + (myPid + 1));
        generatorThread = new Thread(this::poissonGeneratorLoop, "generator-" + (myPid + 1));

        listenerThread.start();
        processThread.start();

        // Give time for others to start listening (pragmatic)
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        generatorThread.start();
    }

    // Para a execução do peer e fecha o socket do servidor
    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    // Aguarda o término das threads principais do peer
    public void join() throws InterruptedException {
        if (listenerThread != null) listenerThread.join();
        if (processThread != null) processThread.join();
        if (generatorThread != null) generatorThread.join();
    }

    // ----------------- Core algorithm -----------------
    
    // Gera uma palavra aleatória do dicionário e envia para todos os peers (DATA + ACK)
    private void multicastRandomWord() {
        // Local event: increment Lamport for DATA
        long ts = lamport.incrementAndGet();
        updateMyLastSeen(ts);

        String word = dictionaryWords.get(ThreadLocalRandom.current().nextInt(dictionaryWords.size()));
        String msgId = UUID.randomUUID().toString();

        Event data = Event.data(msgId, myPid, ts, word);

        // SEND log
        System.out.printf("[PID %d] SEND   word=\"%s\" | ts=%d | msgId=%s%n", 
            (myPid + 1), word, ts, msgId);

        // Enqueue locally
        enqueueDataIfNew(data);

        // Multicast DATA to others
        multicastToAllExceptMe(data);

        // Multicast ACK from sender with incremented Lamport
        long ackTs = lamport.incrementAndGet();
        updateMyLastSeen(ackTs);
        Event ack = Event.ack(msgId, myPid, ackTs);
        multicastToAllExceptMe(ack);
    }


    // Processa um evento recebido (DATA ou ACK), atualiza relógio Lamport e envia ACK se necessário
    private void onReceive(Event evt) {
        int remotePid = evt.getSenderPid();

        // Update Lamport clock correctly
        long receivedTs = evt.getLamportTs();
        long local = lamport.get();
        long newLocal = Math.max(local, receivedTs) + 1;
        lamport.set(newLocal);
        updateMyLastSeen(newLocal);

        // Track last seen from remote
        if (remotePid >= 0 && remotePid < lastSeenLamport.length) {
            lastSeenLamport[remotePid].updateAndGet(current -> Math.max(current, receivedTs));
        }

        if (evt.getType() == Event.Type.DATA) {
            boolean added = enqueueDataIfNew(evt);
            if (added) {
                // Send ACK (multicast) with incremented Lamport time
                long ackTs = lamport.incrementAndGet();
                updateMyLastSeen(ackTs);
                Event ack = Event.ack(evt.getMsgId(), myPid, ackTs);
                multicastToAllExceptMe(ack);
            }
        }
        // ACK needs no queueing
    }

    // Update our own lastSeenLamport entry atomically.
    
    // Atualiza o último timestamp Lamport visto para este peer
    private void updateMyLastSeen(long timestamp) {
        lastSeenLamport[myPid].updateAndGet(current -> Math.max(current, timestamp));
    }

    /** Condition to process the head message (Tanenbaum/van Steen style) */
    // Verifica se o evento no topo da fila pode ser processado (todos os peers já viram o timestamp)
    private boolean canProcess(Event head) {
        long t = head.getLamportTs();
        for (int i = 0; i < lastSeenLamport.length; i++) {
            if (lastSeenLamport[i].get() < t) return false;
        }
        return true;
    }

    // Loop principal que processa eventos da fila de acordo com a ordem causal
    private void processLoop() {
        while (running) {
            try {
                Event head = holdback.peek();
                if (head == null) {
                    Thread.sleep(10);
                    continue;
                }

                if (canProcess(head)) {
                    // Remove and deliver
                    holdback.poll();
                    
                    // Build lastSeen snapshot for logging
                    long[] snapshot = new long[lastSeenLamport.length];
                    for (int i = 0; i < lastSeenLamport.length; i++) {
                        snapshot[i] = lastSeenLamport[i].get();
                    }
                    
                    System.out.printf(
                        "[PID %d] PROCESS word=\"%s\" | orderKey=(ts=%d, origin=%d) | msgId=%s | lastSeen=%s%n",
                        (myPid + 1),
                        head.getWord(),
                        head.getLamportTs(),
                        (head.getOriginPid() + 1),
                        head.getMsgId(),
                        Arrays.toString(snapshot)
                    );
                } else {
                    // Wait for more ACK/DATA to advance lastSeen from everyone
                    Thread.sleep(2);
                }
            } catch (InterruptedException ignored) {}
            catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Adiciona evento DATA à fila se ainda não foi visto (deduplicação por msgId)
    private boolean enqueueDataIfNew(Event data) {
        // Dedup by msgId
        if (!seenData.add(data.getMsgId())) return false;
        holdback.add(data);
        return true;
    }

    // ----------------- Networking -----------------

    // Loop que aceita conexões de outros peers e delega o tratamento de cada cliente
    private void listenLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client), "conn-" + (myPid + 1) + "-" + System.nanoTime()).start();
            } catch (SocketException se) {
                // Likely closed during stop()
                if (running) se.printStackTrace();
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    // Processa uma conexão recebida, lendo e tratando o evento enviado
    private void handleClient(Socket client) {
        try (ObjectInputStream in = new ObjectInputStream(client.getInputStream())) {
            Object obj = in.readObject();
            if (obj instanceof Event) {
                onReceive((Event) obj);
            }
        } catch (EOFException ignored) {
        } catch (Exception e) {
            // Noisy networks happen; log and continue
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    // Envia um evento para todos os peers exceto este
    private void multicastToAllExceptMe(Event evt) {
        for (PeerInfo p : peers.values()) {
            if (p.pid == myPid) continue;
            sendOne(p, evt);
        }
    }

    // Envia um evento para um peer específico, com até 3 tentativas
    private void sendOne(PeerInfo target, Event evt) {
        // Best-effort retry a couple times (simple robustness)
        int tries = 0;
        while (tries < 3 && running) {
            tries++;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(target.host, target.port), 800);
                try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
                    out.writeObject(evt);
                    out.flush();
                }
                return;
            } catch (IOException e) {
                // short backoff
                try { Thread.sleep(50L * tries); } catch (InterruptedException ignored) {}
            }
        }
    }

    // ----------------- Poisson generator (rate = 1 msg/sec) -----------------

    // Gera eventos DATA aleatórios com intervalo exponencial (Poisson, 1 msg/s)
    private void poissonGeneratorLoop() {
        final double lambda = 1.0; // events per second

        while (running) {
            try {
                double u = ThreadLocalRandom.current().nextDouble();
                // Exponential inter-arrival time: -ln(U)/lambda (seconds)
                double intervalSec = -Math.log(1.0 - u) / lambda;
                long sleepMs = Math.max(1L, (long) (intervalSec * 1000.0));
                Thread.sleep(sleepMs);

                multicastRandomWord();
            } catch (InterruptedException ignored) {}
            catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // ----------------- File loading -----------------

    // Carrega a tabela de peers a partir de um arquivo
    private static Map<Integer, PeerInfo> loadPeers(String path) throws IOException {
        Map<Integer, PeerInfo> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;

                int pid = Integer.parseInt(parts[0]) - 1; // convert 1..6 to 0..5
                if (pid < 0 || pid >= 6) {
                    throw new IllegalArgumentException("Peer IDs must be between 1 and 6");
                }

                String host = parts[1];
                int port = Integer.parseInt(parts[2]);

                if (map.containsKey(pid)) {
                    throw new IllegalArgumentException("Duplicate pid in ip_table: " + pid);
                }
                map.put(pid, new PeerInfo(pid, host, port));
            }
        }

        if (map.size() != 6) {
            System.err.println("WARNING: ip_table has " + map.size() + " peers; assignment asks for 6.");
        }
        return map;
    }

    // Carrega as palavras do dicionário a partir de um arquivo
    private static List<String> loadDictionary(String path) throws IOException {
        List<String> words = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // basic cleanup: keep simple word tokens
                line = line.replaceAll("[^A-Za-zÀ-ÿ-]", "");
                if (line.length() < 2) continue;
                words.add(line);
            }
        }
        return words;
    }

    // ----------------- Main entrypoint -----------------

    // Ponto de entrada: inicializa o peer com os argumentos fornecidos
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: java ds.assignment.tom.Peer <pid(1-6)> <ip_table.txt> <dictionary.txt>");
            System.exit(1);
        }
        int myPid = Integer.parseInt(args[0]) - 1;
        String ipTable = args[1];
        String dict = args[2];
        try {
            Peer peer = new Peer(myPid, ipTable, dict);
            peer.start();
            peer.join();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }
}
