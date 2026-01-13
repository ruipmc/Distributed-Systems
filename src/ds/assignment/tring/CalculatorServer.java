package ds.assignment.tring;

import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.concurrent.*;

public class CalculatorServer {

    private final int port;
    private final ExecutorService pool;

    public CalculatorServer(int port, int workers) {
        this.port = port;
        this.pool = Executors.newFixedThreadPool(workers);
    }

    // Inicia o servidor, aceita conexões e delega o tratamento de cada cliente para uma thread do pool
    public void start() throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("[SERVER] Starting Calculator Server on port " + port);
            System.out.println("[SERVER] Server ready and listening...");
            while (true) {
                Socket client = ss.accept();
                pool.submit(() -> handleClient(client));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // Processa uma requisição de um cliente, executando a operação matemática solicitada
    private void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream()))) {

            String line = in.readLine();
            if (line == null || line.isBlank()) return;

            // Force dot decimal parsing/formatting
            Locale.setDefault(Locale.US);

            String[] parts = line.trim().split("\\s+");
            if (parts.length < 5) {
                out.write("ERR malformed_request\n");
                out.flush();
                return;
            }

            String op = parts[0].toLowerCase(Locale.ROOT);
            double a = Double.parseDouble(parts[1]);
            double b = Double.parseDouble(parts[2]);
            long q = Long.parseLong(parts[3]);
            String peerId = parts[4];

            double res;
            switch (op) {
                case "add" -> res = a + b;
                case "sub" -> res = a - b;
                case "mul" -> res = a * b;
                case "div" -> {
                    if (b == 0.0) {
                        out.write(q + " NaN\n");
                        out.flush();
                        System.out.println("[SERVER] " + peerId + " q=" + q + " div by zero");
                        return;
                    }
                    res = a / b;
                }
                default -> {
                    out.write(q + " NaN\n");
                    out.flush();
                    System.out.println("[SERVER] " + peerId + " q=" + q + " unknown op=" + op);
                    return;
                }
            }

            out.write(q + " " + res + "\n");
            out.flush();

            System.out.println("[SERVER] " + peerId + " q=" + q + " " + op + "(" + a + "," + b + ")=" + res);

        } catch (Exception e) {
            // Keep server alive; log and move on
            System.err.println("[SERVER] Client handler error: " + e.getMessage());
        }
    }

    // Ponto de entrada: inicializa o servidor com os argumentos fornecidos
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java ds.assignment.tring.CalculatorServer <port> [workers]");
            return;
        }
        int port = Integer.parseInt(args[0]);
        int workers = (args.length >= 2) ? Integer.parseInt(args[1]) : 32;
        new CalculatorServer(port, workers).start();
    }
}