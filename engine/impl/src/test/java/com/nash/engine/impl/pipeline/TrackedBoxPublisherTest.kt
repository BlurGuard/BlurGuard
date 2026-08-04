package com.nash.engine.impl.pipeline

import com.nash.core.model.RenderBoxFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackedBoxPublisherTest {

    @Test
    fun `publishes to renderer and overlay`() {
        val feed = RenderBoxFeed()
        val publisher = TrackedBoxPublisher(feed)
        val boxes = listOf(tracked(id = 3L))

        publisher.publish(boxes, rotationDegrees = 90)

        assertEquals(boxes, feed.latest().boxes)
        assertEquals(90, feed.latest().rotationDegrees)
        assertEquals(boxes, publisher.trackedBoxes.value)
    }

    @Test
    fun `latest publish wins`() {
        val feed = RenderBoxFeed()
        val publisher = TrackedBoxPublisher(feed)

        publisher.publish(listOf(tracked(id = 1L)), 0)
        publisher.publish(listOf(tracked(id = 2L)), 0)

        assertEquals(1, publisher.trackedBoxes.value.size)
        assertEquals(2L, publisher.trackedBoxes.value.single().id.value)
    }

    @Test
    fun `reset clears both consumers`() {
        val feed = RenderBoxFeed()
        val publisher = TrackedBoxPublisher(feed)
        publisher.publish(listOf(tracked()), 180)

        publisher.reset()

        assertTrue(feed.latest().boxes.isEmpty())
        assertEquals(0, feed.latest().rotationDegrees)
        assertTrue(publisher.trackedBoxes.value.isEmpty())
    }
}