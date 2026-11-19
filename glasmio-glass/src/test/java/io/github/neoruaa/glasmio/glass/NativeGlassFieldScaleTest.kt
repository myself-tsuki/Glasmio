package io.github.neoruaa.glasmio.glass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGlassFieldScaleTest {
    private val field = NativeGlassFilterField("""
uniform float2 fieldScale;
float3 navigationGlassShapeField(float2 point) { return float3(length(point * fieldScale), 1.0, 0.0); }
float3 navigationGlassField(float2 point) { return navigationGlassShapeField(point); }
""") { }

    @Test fun fullScaleKeepsTheOriginalField() {
        assertSame(field, field.atSamplingScale(1f))
        assertSame(field, field.atSamplingScale(Float.NaN))
    }

    @Test fun reducedScaleWrapsEveryEntryPointOnce() {
        val source = field.atSamplingScale(.5f).shaderFunctions
        for (name in listOf("navigationGlassShapeField", "navigationGlassField",
            "navigationGlassShadowDistance", "navigationGlassOpticalFieldFromShape")) {
            assertTrue(name, source.contains("glasmioFullSize_$name("))
            assertTrue(name, Regex("\\b(float3|float) $name\\(").findAll(source).count() == 1)
        }
        // Internal calls stay in full-size space.
        assertTrue(source.contains("return glasmioFullSize_navigationGlassShapeField(point); }"))
        assertTrue(source.contains("point / glasmioFieldSampleScale"))
        assertFalse(source.contains("glasmioFullSize_glasmioFullSize_"))
    }
}
