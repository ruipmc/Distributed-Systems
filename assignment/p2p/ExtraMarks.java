package ds.assignment.p2p;
import java.io.*;
import java.util.*;

public class ExtraMarks {
    
    private static final boolean DEBUG = true;
    
    private static class SimulationResult {
        int n;
        int run;
        double timeSeconds;
        int edges;
        List<PeerValue> finalValues;
        
        SimulationResult(int n, int run, double time, int edges, List<PeerValue> values) {
            this.n = n;
            this.run = run;
            this.timeSeconds = time;
            this.edges = edges;
            this.finalValues = values;
        }
    }
    
    private static class PeerValue {
        String peerId;
        double value;
        
        PeerValue(String peerId, double value) {
            this.peerId = peerId;
            this.value = value;
        }
    }

    // Ponto de entrada: executa simulações de convergência e salva resultados em CSV
    public static void main(String[] args) throws Exception {
        // Configuração
        List<Integer> nValues = Arrays.asList(6, 8, 10, 12, 15);
        int runs = 1;
        double epsilon = 0.01; // |valor - 1/N| < epsilon
        int basePort = 6000;
        

        System.out.println("EXTRA MARKS: Convergência Anti-Entropy");

        // Inicializar ficheiros CSV (criar headers)
        initializeCsvFiles();
        
        List<SimulationResult> allResults = new ArrayList<>();
        
        for (int n : nValues) {
            System.out.printf("►►► N = %d peers ◄◄◄%n", n);
            
            for (int run = 0; run < runs; run++) {
                long seed = n * 1000L + run;
                int runBasePort = basePort + (n * 100) + (run * 20);
                
                System.out.printf("  Run %d (seed=%d, basePort=%d)...%n", 
                                run + 1, seed, runBasePort);
                
                try {
                    SimulationResult r = runSimulation(n, run, seed, runBasePort, epsilon);
                    allResults.add(r);
                    
                    // ✨ SALVAR IMEDIATAMENTE após cada run
                    appendResultToSummary(r);
                    appendResultToPeerValues(r);
                    
                    System.out.printf("    ✓ Convergiu em %.2fs (arestas=%d)%n", 
                                    r.timeSeconds, r.edges);
                    System.out.printf("    ✓ Dados salvos em convergence_summary.csv e peer_values_n%d.csv%n", r.n);
                    
                } catch (Exception e) {
                    System.err.printf("    ✗ Erro fatal: %s%n", e.getMessage());
                    if (DEBUG) {
                        e.printStackTrace();
                    }
                }
                
                // Limpar portas
                Thread.sleep(3000);
            }
            System.out.println();
        }
        
        // Sumário
        System.out.println("►►► Sumário ◄◄◄");
        printSummary(allResults);
        System.out.println(" Salvo em convergence_summary.csv (tempos e métricas por run)");

    }

    // Inicializa os ficheiros CSV de resultados, criando headers
    private static void initializeCsvFiles() {
        // Criar convergence_summary.csv com header
        try (PrintWriter writer = new PrintWriter("convergence_summary.csv")) {
            writer.println("n,run,time_seconds,converged,edges,target_value,avg_value,min_value,max_value,std_value,max_diff");
            System.out.println("✓ convergence_summary.csv inicializado");
        } catch (IOException e) {
            System.err.println("Erro ao inicializar summary: " + e.getMessage());
        }
    }

    // Adiciona o resultado de uma simulação ao ficheiro convergence_summary.csv
    private static void appendResultToSummary(SimulationResult r) {
        try (PrintWriter writer = new PrintWriter(new FileWriter("convergence_summary.csv", true))) {
            double target = 1.0 / r.n;
            boolean converged = r.timeSeconds > 0; // tempo negativo = timeout
            double absTime = Math.abs(r.timeSeconds);
            
            // Calcular estatísticas dos valores finais
            double sum = 0;
            double min = Double.MAX_VALUE;
            double max = Double.MIN_VALUE;
            double maxDiff = 0;
            
            for (PeerValue pv : r.finalValues) {
                sum += pv.value;
                min = Math.min(min, pv.value);
                max = Math.max(max, pv.value);
                maxDiff = Math.max(maxDiff, Math.abs(pv.value - target));
            }
            
            double avg = sum / r.finalValues.size();
            
            double variance = 0;
            for (PeerValue pv : r.finalValues) {
                variance += Math.pow(pv.value - avg, 2);
            }
            double std = Math.sqrt(variance / r.finalValues.size());
            
            writer.printf("%d,%d,%.3f,%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                        r.n, r.run, absTime, converged, r.edges, 
                        target, avg, min, max, std, maxDiff);
            
        } catch (IOException e) {
            System.err.println("Erro ao adicionar ao summary: " + e.getMessage());
        }
    }

    // Adiciona resultado ao ficheiro peer_values para este N
    private static void appendResultToPeerValues(SimulationResult r) {
        String filename = String.format("peer_values_n%d.csv", r.n);
        File file = new File(filename);
        boolean fileExists = file.exists();
        
        try {
            if (!fileExists) {
                // Criar novo ficheiro com estrutura completa
                try (PrintWriter writer = new PrintWriter(filename)) {
                    // Header: peer_id, run0, run1, run2, ...
                    writer.print("peer_id");
                    for (int run = 0; run < 10; run++) { // Preparar até 10 runs
                        writer.printf(",run%d", run);
                    }
                    writer.println();
                    
                    // Linhas para cada peer (inicialmente vazias)
                    for (int peerIdx = 0; peerIdx < r.n; peerIdx++) {
                        writer.printf("p%d", peerIdx + 1);
                        for (int run = 0; run < 10; run++) {
                            writer.print(",");
                        }
                        writer.println();
                    }
                }
                System.out.printf("    ✓ %s criado%n", filename);
            }
            
            // Ler ficheiro existente
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            
            // Atualizar valores para este run
            for (int peerIdx = 0; peerIdx < r.finalValues.size(); peerIdx++) {
                int lineIdx = peerIdx + 1; // +1 para pular header
                if (lineIdx < lines.size()) {
                    String line = lines.get(lineIdx);
                    String[] parts = line.split(",", -1);
                    
                    // Atualizar coluna do run (run + 1 porque coluna 0 é peer_id)
                    if (r.run + 1 < parts.length) {
                        parts[r.run + 1] = String.format("%.8f", r.finalValues.get(peerIdx).value);
                        lines.set(lineIdx, String.join(",", parts));
                    }
                }
            }
            
            // Escrever ficheiro atualizado
            try (PrintWriter writer = new PrintWriter(filename)) {
                for (String line : lines) {
                    writer.println(line);
                }
            }
            
        } catch (IOException e) {
            System.err.printf("Erro ao atualizar %s: %s%n", filename, e.getMessage());
        }
    }

    // Executa uma simulação de convergência para N peers e retorna o resultado
    private static SimulationResult runSimulation(int n, int run, long seed, int basePort, double epsilon) 
            throws Exception {
        Random rng = new Random(seed);
        
        // Gerar topologia conectada aleatória
        List<int[]> edges = generateConnectedTopology(n, rng);
        
        if (DEBUG) {
            System.out.printf("    Topologia: %d arestas geradas%n", edges.size());
        }
        
        // Criar adjacências
        Map<Integer, Set<Integer>> adjacency = new HashMap<>();
        for (int i = 0; i < n; i++) {
            adjacency.put(i, new HashSet<>());
        }
        for (int[] edge : edges) {
            adjacency.get(edge[0]).add(edge[1]);
            adjacency.get(edge[1]).add(edge[0]);
        }
        
        // Criar e iniciar peers
        List<Peer> peers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String peerId = "p" + (i + 1);
            int port = basePort + i;
            // p1 = 1.0, outros = 0.0
            double initialValue = (i == 0) ? 1.0 : 0.0;
            
            Peer peer = new Peer(peerId, port, initialValue);
            peers.add(peer);
            peer.start();
        }
        
        // Esperar peers iniciarem
        Thread.sleep(1500);
        
        // Construir rede via registos
        Set<String> processedEdges = new HashSet<>();
        int registrations = 0;
        
        for (int[] edge : edges) {
            int i = edge[0];
            int j = edge[1];
            
            String edgeKey = Math.min(i, j) + "," + Math.max(i, j);
            if (processedEdges.contains(edgeKey)) {
                continue;
            }
            processedEdges.add(edgeKey);
            
            // Peer i regista-se com peer j
            Peer peerI = peers.get(i);
            int portJ = basePort + j;
            peerI.registerWith("localhost", portJ);
            
            // Peer j regista-se com peer i
            Peer peerJ = peers.get(j);
            int portI = basePort + i;
            peerJ.registerWith("localhost", portI);
            
            registrations += 2;
            Thread.sleep(50);
        }
        
        if (DEBUG) {
            System.out.printf("    %d registos completados%n", registrations);
        }
        
        // Esperar registos completarem
        Thread.sleep(3000);
        
        // Medir convergência
        double target = 1.0 / n;
        long startTime = System.currentTimeMillis();
        int checkCount = 0;
        while (true) {
            Thread.sleep(500);
            checkCount++;

            boolean converged = true;
            double minValue = Double.MAX_VALUE;
            double maxValue = Double.MIN_VALUE;
            double maxDiff = 0;

            for (Peer peer : peers) {
                double value = peer.getValue();
                double diff = Math.abs(value - target);

                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);
                maxDiff = Math.max(maxDiff, diff);

                if (diff > epsilon) {
                    converged = false;
                }
            }

            // Debug periódico
            if (DEBUG && checkCount % 10 == 0) {
                double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                System.out.printf("    [%.1fs] range=[%.6f, %.6f], maxDiff=%.6f, target=%.6f%n",
                                elapsed, minValue, maxValue, maxDiff, target);
            }

            if (converged) {
                double timeSeconds = (System.currentTimeMillis() - startTime) / 1000.0;

                // Capturar valores finais
                List<PeerValue> finalValues = new ArrayList<>();
                for (int i = 0; i < peers.size(); i++) {
                    Peer peer = peers.get(i);
                    String peerId = "p" + (i + 1);
                    finalValues.add(new PeerValue(peerId, peer.getValue()));
                }

                // Parar todos os peers
                for (Peer peer : peers) {
                    peer.stop();
                }
                Thread.sleep(500);

                if (DEBUG) {
                    System.out.printf("    ✓ Convergência após %d checks%n", checkCount);
                }

                return new SimulationResult(n, run, timeSeconds, edges.size(), finalValues);
            }
        }
    }

    
    // Cria Spanning Tree + Arestas extras com probabilidade p, neste caso 15%
    private static List<int[]> generateConnectedTopology(int n, Random rng) {
        Set<String> edgeSet = new HashSet<>();
        
        // Spanning tree: cada nó i>0 conecta a um nó j<i aleatório
        for (int i = 1; i < n; i++) {
            int parent = rng.nextInt(i);
            int lo = Math.min(i, parent);
            int hi = Math.max(i, parent);
            edgeSet.add(lo + "," + hi);
        }
        
        // Arestas extras (15% de probabilidade)
        double extraEdgeProb = 0.15;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                String key = i + "," + j;
                if (!edgeSet.contains(key) && rng.nextDouble() < extraEdgeProb) {
                    edgeSet.add(key);
                }
            }
        }
        
        // Converter para lista
        List<int[]> edges = new ArrayList<>();
        for (String key : edgeSet) {
            String[] parts = key.split(",");
            int i = Integer.parseInt(parts[0]);
            int j = Integer.parseInt(parts[1]);
            edges.add(new int[]{i, j});
        }
        
        return edges;
    }

    // Imprime um sumário dos resultados das simulações (médias, desvios, etc)
    private static void printSummary(List<SimulationResult> results) {
        Map<Integer, List<Double>> timesByN = new HashMap<>();
        Map<Integer, List<Integer>> edgesByN = new HashMap<>();
        
        for (SimulationResult r : results) {
            timesByN.computeIfAbsent(r.n, k -> new ArrayList<>()).add(r.timeSeconds);
            edgesByN.computeIfAbsent(r.n, k -> new ArrayList<>()).add(r.edges);
        }
        
        System.out.println("N\tArestas\tTempo(s)\tMin(s)\tMax(s)\tStd(s)");
        System.out.println("──────────────────────────────────────────────────────");
        
        List<Integer> ns = new ArrayList<>(timesByN.keySet());
        Collections.sort(ns);
        
        for (int n : ns) {
            List<Double> times = timesByN.get(n);
            List<Integer> edges = edgesByN.get(n);
            
            double avgTime = times.stream().mapToDouble(d -> d).average().orElse(0);
            double minTime = times.stream().mapToDouble(d -> d).min().orElse(0);
            double maxTime = times.stream().mapToDouble(d -> d).max().orElse(0);
            
            double variance = times.stream()
                .mapToDouble(d -> Math.pow(d - avgTime, 2))
                .average().orElse(0);
            double std = Math.sqrt(variance);
            
            double avgEdges = edges.stream().mapToInt(i -> i).average().orElse(0);
            
            System.out.printf("%d\t%.1f\t%.2f\t\t%.2f\t%.2f\t%.2f%n", 
                            n, avgEdges, avgTime, minTime, maxTime, std);
        }
    }
}