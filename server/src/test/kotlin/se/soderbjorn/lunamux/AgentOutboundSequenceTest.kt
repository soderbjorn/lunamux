/**
 * Tests the ordered outbound stream + attach-gating discipline shared by all
 * [TermSession]s, exercised through the PTY-less [AgentSession] because it is
 * fully controllable (output is pushed via emitOutput, sizes via setClientSize)
 * and needs no process:
 *  - output produces Output events with strictly increasing seq;
 *  - a size change produces a Size event;
 *  - attachPayload captures the current seq and a snapshot of prior output, and
 *    events emitted afterwards carry a greater seq (the gate the `/pty` writer uses);
 *  - under concurrent output + resizes every seq is unique and strictly
 *    increasing (the outbound monitor serializes assignment).
 *
 * The grid-reflow specifics of [TerminalSession] (a cols change synthesizing a
 * RIS resync redraw) are covered by GridSerializerRoundTripTest + PtyAttachFlowTest
 * and verified end-to-end on-device in the phase checklist.
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class AgentOutboundSequenceTest {

    private suspend fun collectFor(ch: Channel<SessionEvent>, ms: Long): List<SessionEvent> {
        val out = mutableListOf<SessionEvent>()
        var elapsed = 0L
        while (elapsed < ms) {
            var e = ch.tryReceive().getOrNull()
            while (e != null) {
                out.add(e)
                e = ch.tryReceive().getOrNull()
            }
            delay(20)
            elapsed += 20
        }
        return out
    }

    @Test
    fun `output and size produce ordered events with increasing seq`() = runBlocking {
        val session = AgentSession(renderMode = "transcript", initialCols = 80, initialRows = 24)
        val ch = Channel<SessionEvent>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) { session.events.collect { ch.trySend(it) } }
        try {
            delay(100)
            session.emitOutput("alpha".toByteArray())
            session.emitOutput("beta".toByteArray())
            session.setClientSize("c1", 70, 20)
            val evs = collectFor(ch, 300)

            val outputs = evs.filterIsInstance<SessionEvent.Output>()
            assertTrue(outputs.size >= 2, "expected the two Output events")
            assertTrue(evs.any { it is SessionEvent.Size && it.cols == 70 && it.rows == 20 }, "expected a Size event")

            var prev = Long.MIN_VALUE
            for (e in evs) {
                assertTrue(e.seq > prev, "seq must strictly increase: ${e.seq} after $prev")
                prev = e.seq
            }
        } finally {
            collector.cancel()
            session.shutdown()
        }
    }

    @Test
    fun `attachPayload snapshots prior output and gates later events`() = runBlocking {
        val session = AgentSession(renderMode = "transcript", initialCols = 80, initialRows = 24)
        try {
            session.emitOutput("BEFORE".toByteArray())
            val attach = session.attachPayload()
            assertTrue(attach.bytes.toString(Charsets.UTF_8).contains("BEFORE"), "snapshot should hold prior output")

            val ch = Channel<SessionEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.IO) { session.events.collect { ch.trySend(it) } }
            try {
                delay(100)
                session.emitOutput("AFTER".toByteArray())
                val evs = collectFor(ch, 200)
                val fresh = evs.filterIsInstance<SessionEvent.Output>().filter { it.seq > attach.seq }
                assertTrue(
                    fresh.any { it.bytes.toString(Charsets.UTF_8).contains("AFTER") },
                    "post-attach output must carry seq > attach.seq",
                )
            } finally {
                collector.cancel()
            }
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `seqs are unique and increasing under concurrent output and resizes`() = runBlocking {
        val session = AgentSession(renderMode = "transcript", initialCols = 80, initialRows = 24)
        val ch = Channel<SessionEvent>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) { session.events.collect { ch.trySend(it) } }
        try {
            delay(100)
            val jobs = (0 until 6).map { k ->
                launch(Dispatchers.Default) {
                    for (i in 0 until 20) {
                        if (i % 3 == 0) session.setClientSize("c$k", 40 + ((k * 20 + i) % 50), 24)
                        else session.emitOutput("k$k-$i ".toByteArray())
                        delay(2)
                    }
                }
            }
            jobs.forEach { it.join() }
            val evs = collectFor(ch, 400)

            assertTrue(evs.isNotEmpty(), "expected events")
            val seqs = evs.map { it.seq }
            assertTrue(seqs.toSet().size == seqs.size, "seqs must be unique")
            var prev = Long.MIN_VALUE
            for (s in seqs) {
                assertTrue(s > prev, "seqs must strictly increase")
                prev = s
            }
        } finally {
            collector.cancel()
            session.shutdown()
        }
    }
}
