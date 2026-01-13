# Project Quick Run Guide

This README explains how to compile and run the three example packages: TRING, P2P, and TOM.

All commands below assume you are in the `src` folder.

---

## TRING

### Compile
```bash
javac ds/assignment/tring/*.java
```

### Start the calculator server
Open one terminal:
```bash
java ds.assignment.tring.CalculatorServer 7000
```

### Start the 5 peers (one terminal per peer)
```bash
java ds.assignment.tring.Peer p1 5001 127.0.0.1 5002 127.0.0.1 7000
java ds.assignment.tring.Peer p2 5002 127.0.0.1 5003 127.0.0.1 7000
java ds.assignment.tring.Peer p3 5003 127.0.0.1 5004 127.0.0.1 7000
java ds.assignment.tring.Peer p4 5004 127.0.0.1 5005 127.0.0.1 7000
java ds.assignment.tring.Peer p5 5005 127.0.0.1 5001 127.0.0.1 7000
```

### Inject the token
In any peer terminal, type:
```text
start
```

### ExtraMarks

To observe the Extra Marks feature (fault tolerance with ACKs), start all peers and the server as described. Then, terminate (Ctrl+C) any one peer’s terminal. You’ll see that the token continues to circulate and the remaining peers keep processing requests, demonstrating that the ring tolerates peer failures and maintains operation.

---

## P2P

### Compile
```bash
javac ds/assignment/p2p/*.java
```

### Start the 6 peers (one terminal per peer)
```bash
java ds.assignment.p2p.Peer p1 5001
java ds.assignment.p2p.Peer p2 5002
java ds.assignment.p2p.Peer p3 5003
java ds.assignment.p2p.Peer p4 5004
java ds.assignment.p2p.Peer p5 5005
java ds.assignment.p2p.Peer p6 5006
```

Peers register neighbors and synchronize values automatically.


### ExtraMarks

For the ExtraMarks, I used 6, 8, 10, 12 and 15 peers. The convergence time cna be checked in the convergence_summary.png, which was created with the convergence_summary.csv
---

## TOM (Lamport Total Order)

### Compile
```bash
javac ds/assignment/tom/*.java
```

### Files
Ensure `ip_table.txt` and `dictionary.txt` exist in `ds/assignment/tom/`.

### Start the 6 peers (one terminal per peer)
```bash
java ds.assignment.tom.Peer 1 ds/assignment/tom/ip_table.txt ds/assignment/tom/dictionary.txt
java ds.assignment.tom.Peer 2 ds/assignment/tom/ip_table.txt ds/assignment/tom/dictionary.txt
java ds.assignment.tom.Peer 3 ds/assignment/tom/ip_table.txt ds/assignment/tom/dictionary.txt
java ds.assignment.tom.Peer 4 ds/assignment/tom/ip_table.txt ds/assignment/tom/dictionary.txt
java ds.assignment.tom.Peer 5 ds/assignment/tom/ip_table.txt ds/assignment/tom/dictionary.txt
java ds.assignment.tom.Peer 6 ds/assignment/tom/ip_table.txt ds/assignment/tom/dictionary.txt
```

Each peer runs in its own terminal. Peers read endpoints from `ip_table.txt` and words from `dictionary.txt`.

---