package com.bizzarosn.heightmark

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialConversionTest {

    /** Unconfined, so every launch and resume runs inline and the tests need no scheduler. */
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    private val gates = mutableMapOf<Int, CompletableDeferred<String>>()
    private val started = mutableListOf<Int>()
    private val delivered = mutableListOf<Pair<Int, String>>()

    private val conversion = SerialConversion<Int, String>(
        convert = { item ->
            started += item
            gates.getValue(item).await()
        },
        deliver = { item, result -> delivered += item to result }
    )

    private fun gate(vararg items: Int) {
        items.forEach { gates[it] = CompletableDeferred() }
    }

    @After
    fun tearDown() {
        job.cancel()
    }

    @Test
    fun `items convert and deliver in arrival order`() {
        gate(1, 2, 3)
        conversion.start(scope)

        conversion.submit(1)
        gates.getValue(1).complete("one")
        conversion.submit(2)
        gates.getValue(2).complete("two")
        conversion.submit(3)
        gates.getValue(3).complete("three")

        assertEquals(listOf(1 to "one", 2 to "two", 3 to "three"), delivered)
    }

    @Test
    fun `a second conversion never starts while one is in flight`() {
        gate(1, 2)
        conversion.start(scope)

        conversion.submit(1)
        conversion.submit(2)

        assertEquals(listOf(1), started)
        gates.getValue(1).complete("one")
        assertEquals(listOf(1, 2), started)
    }

    @Test
    fun `only the newest waiting item survives a slow conversion`() {
        gate(1, 2, 3, 4)
        conversion.start(scope)

        conversion.submit(1)
        conversion.submit(2)
        conversion.submit(3)
        conversion.submit(4)
        gates.getValue(1).complete("one")
        gates.getValue(4).complete("four")

        assertEquals(listOf(1, 4), started)
        assertEquals(listOf(1 to "one", 4 to "four"), delivered)
    }

    @Test
    fun `stop discards the waiting item and the in-flight result`() {
        gate(1, 2)
        conversion.start(scope)
        conversion.submit(1)
        conversion.submit(2)

        conversion.stop()
        gates.getValue(1).complete("one")

        assertTrue(delivered.isEmpty())
        assertEquals(listOf(1), started)
    }

    @Test
    fun `a restart does not replay what stop discarded`() {
        gate(1, 2, 3)
        conversion.start(scope)
        conversion.submit(1)
        conversion.submit(2)
        conversion.stop()

        conversion.start(scope)
        conversion.submit(3)
        gates.getValue(3).complete("three")

        assertEquals(listOf(3 to "three"), delivered)
        assertEquals(listOf(1, 3), started)
    }

    @Test
    fun `submit before start is dropped`() {
        gate(1)

        conversion.submit(1)
        conversion.start(scope)

        assertTrue(started.isEmpty())
    }

    @Test
    fun `start while running keeps the one consumer`() {
        gate(1, 2)
        conversion.start(scope)
        conversion.start(scope)

        conversion.submit(1)
        conversion.submit(2)

        assertEquals(listOf(1), started)
    }
}
