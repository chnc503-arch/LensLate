package com.lenslate.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AffineTransform2DTest {

    @Test
    fun inverseRoundTripsRotatedScaledCoordinates() {
        val transform = AffineTransform2D(
            m00 = 0.32f,
            m01 = -0.18f,
            m02 = 42f,
            m10 = 0.18f,
            m11 = 0.32f,
            m12 = -17f,
        )
        val inverse = assertNotNullAndReturn(transform.inverse())
        val original = Point2D(128.5f, 642.25f)

        val restored = inverse.map(transform.map(original))

        assertEquals(original.x, restored.x, 0.001f)
        assertEquals(original.y, restored.y, 0.001f)
    }

    @Test
    fun thenAppliesTheNextTransformAfterThisOne() {
        val transform = AffineTransform2D.scale(2f, 3f)
            .then(AffineTransform2D.translation(5f, -4f))

        val result = transform.map(Point2D(4f, 2f))

        assertEquals(13f, result.x, 0f)
        assertEquals(2f, result.y, 0f)
    }

    @Test
    fun singularTransformHasNoInverse() {
        assertNull(AffineTransform2D.scale(0f, 1f).inverse())
    }

    private fun assertNotNullAndReturn(transform: AffineTransform2D?): AffineTransform2D {
        assertNotNull(transform)
        return transform!!
    }
}
