package ds.assignment.tom;

import java.io.Serializable;
import java.util.Objects;


public class Event implements Serializable, Comparable<Event> {

    private static final long serialVersionUID = 1L;

    public enum Type { DATA, ACK }

    private Type type;
    private String msgId;
    private int originPid;   // For DATA
    private int senderPid;   // For ACK (and equals origin for DATA)
    private long lamportTs;
    private String word;     // Only for DATA

    // Cria um evento do tipo DATA com os parâmetros fornecidos
    public static Event data(String msgId, int originPid, long lamportTs, String word) {
        Event e = new Event();
        e.type = Type.DATA;
        e.msgId = msgId;
        e.originPid = originPid;
        e.senderPid = originPid;
        e.lamportTs = lamportTs;
        e.word = word;
        return e;
    }

    // Cria um evento do tipo ACK com os parâmetros fornecidos
    public static Event ack(String msgId, int senderPid, long lamportTs) {
        Event e = new Event();
        e.type = Type.ACK;
        e.msgId = msgId;
        e.senderPid = senderPid;
        e.originPid = -1;
        e.lamportTs = lamportTs;
        e.word = null;
        return e;
    }

    // Retorna o tipo do evento (DATA ou ACK)
    public Type getType() { return type; }
    // Retorna o identificador único da mensagem
    public String getMsgId() { return msgId; }
    // Retorna o PID de origem do evento DATA
    public int getOriginPid() { return originPid; }
    // Retorna o PID do peer que enviou o evento
    public int getSenderPid() { return senderPid; }
    // Retorna o timestamp Lamport do evento
    public long getLamportTs() { return lamportTs; }
    // Retorna a palavra associada ao evento DATA
    public String getWord() { return word; }

    // Total order comparator: (lamportTs, originPid, msgId)
    @Override
    // Compara dois eventos para ordenação total: (lamportTs, originPid, msgId)
    public int compareTo(Event other) {
        int c = Long.compare(this.lamportTs, other.lamportTs);
        if (c != 0) return c;
        c = Integer.compare(this.originPid, other.originPid);
        if (c != 0) return c;
        return this.msgId.compareTo(other.msgId);
    }

    @Override
    // Verifica se dois eventos são iguais (mesmo msgId e tipo)
    public boolean equals(Object o) {
        if (!(o instanceof Event)) return false;
        Event other = (Event) o;
        return Objects.equals(this.msgId, other.msgId) && this.type == other.type;
    }

    @Override
    // Gera o hashCode do evento baseado no tipo e msgId
    public int hashCode() {
        return Objects.hash(type, msgId);
    }

    @Override
    // Retorna uma representação textual do evento
    public String toString() {
        if (type == Type.DATA) {
            return "DATA{msgId=" + msgId + ", originPid=" + originPid + ", ts=" + lamportTs + ", word=" + word + "}";
        }
        return "ACK{msgId=" + msgId + ", senderPid=" + senderPid + ", ts=" + lamportTs + "}";
    }
}