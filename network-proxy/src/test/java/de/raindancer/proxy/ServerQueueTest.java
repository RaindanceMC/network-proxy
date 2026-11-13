package de.raindancer.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServerQueueTest {

    private final UUID ada = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();
    private final UUID di = UUID.randomUUID();

    private static Properties config(String... pairs) {
        Properties p = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            p.setProperty(pairs[i], pairs[i + 1]);
        }
        return p;
    }

    @Test
    @DisplayName("off without queue.server or a limit; read from config.properties with Prime and staff defaults")
    void settings() {
        assertThat(ServerQueue.Settings.from(config())).isEmpty();
        assertThat(ServerQueue.Settings.from(config("queue.server", "smp", "queue.max", "0"))).isEmpty();
        ServerQueue.Settings on = ServerQueue.Settings.from(config("queue.server", "SMP", "queue.max", "100",
                "queue.limbo", "welcome")).orElseThrow();
        assertThat(on.server()).isEqualTo("smp");
        assertThat(on.max()).isEqualTo(100);
        assertThat(on.limbo()).isEqualTo("welcome");
        assertThat(on.priorityPermission()).isEqualTo("rains.prime");
        assertThat(on.bypassPermission()).isEqualTo("rainsnetworkproxy.queue.bypass");
    }

    @Test
    @DisplayName("Prime goes before everybody else; inside each group, who came first goes first")
    void order() {
        ServerQueue queue = new ServerQueue();
        queue.join(ada, false, 1);
        queue.join(bo, false, 2);
        queue.join(cy, true, 3);
        queue.join(di, true, 4);

        assertThat(queue.position(cy)).isEqualTo(1);
        assertThat(queue.position(di)).isEqualTo(2);
        assertThat(queue.position(ada)).isEqualTo(3);
        assertThat(queue.position(bo)).isEqualTo(4);
        assertThat(queue.size()).isEqualTo(4);
        assertThat(queue.next()).contains(cy);
        assertThat(queue.next()).contains(di);
        assertThat(queue.next()).contains(ada);
    }

    @Test
    @DisplayName("joining twice keeps your place; leaving frees it; an empty queue gives nobody")
    void joinLeave() {
        ServerQueue queue = new ServerQueue();
        queue.join(ada, false, 1);
        queue.join(bo, false, 2);
        queue.join(ada, false, 3);
        assertThat(queue.position(ada)).isEqualTo(1);
        queue.leave(ada);
        assertThat(queue.position(ada)).isZero();
        assertThat(queue.position(bo)).isEqualTo(1);
        queue.leave(bo);
        assertThat(queue.next()).isEmpty();
    }

    @Test
    @DisplayName("getting Prime while waiting moves you up at once")
    void upgrade() {
        ServerQueue queue = new ServerQueue();
        queue.join(ada, false, 1);
        queue.join(bo, false, 2);
        queue.join(bo, true, 3);
        assertThat(queue.position(bo)).isEqualTo(1);
    }

    @Test
    @DisplayName("in while there is room and nobody waits; staff always; once full, everybody else waits")
    void admit() {
        assertThat(ServerQueue.admit(99, 100, false, false, true)).isTrue();
        assertThat(ServerQueue.admit(100, 100, false, false, true)).isFalse();
        assertThat(ServerQueue.admit(150, 100, true, false, false)).as("staff").isTrue();
        assertThat(ServerQueue.admit(99, 100, false, false, false)).as("others are waiting already").isFalse();
        assertThat(ServerQueue.admit(99, 100, false, true, false)).as("the one being moved in").isTrue();
        assertThat(ServerQueue.admit(100, 100, false, true, false)).as("moved in, but the slot was taken").isFalse();
    }
}
