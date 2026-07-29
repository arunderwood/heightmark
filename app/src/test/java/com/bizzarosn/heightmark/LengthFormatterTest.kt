package com.bizzarosn.heightmark

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LengthFormatterTest {

    @Test
    fun `metric detail uses one decimal and the meters template`() {
        val detail = LengthFormatter.detail(112.44, useMetric = true, locale = Locale.US)
        assertEquals(R.string.value_meters, detail.templateRes)
        assertEquals("112.4", detail.valueText)
    }

    @Test
    fun `imperial detail converts to whole feet and uses the feet template`() {
        val detail = LengthFormatter.detail(100.0, useMetric = false, locale = Locale.US)
        assertEquals(R.string.value_feet, detail.templateRes)
        // 100 m = 328.084 ft, rendered with no decimals
        assertEquals("328", detail.valueText)
    }

    @Test
    fun `detail honors the locale decimal separator`() {
        val detail = LengthFormatter.detail(112.44, useMetric = true, locale = Locale.GERMANY)
        assertEquals("112,4", detail.valueText)
    }

    @Test
    fun `metric hero value rounds to the nearest meter`() {
        assertEquals(112, LengthFormatter.heroValue(112.44, useMetric = true))
        assertEquals(113, LengthFormatter.heroValue(112.51, useMetric = true))
        // kotlin.math.round is half-to-even (rint): a .5 tie goes to the even neighbor
        assertEquals(112, LengthFormatter.heroValue(112.5, useMetric = true))
        assertEquals(114, LengthFormatter.heroValue(113.5, useMetric = true))
    }

    @Test
    fun `imperial hero value converts before rounding`() {
        // 100 m = 328.084 ft
        assertEquals(328, LengthFormatter.heroValue(100.0, useMetric = false))
    }

    @Test
    fun `unit resources follow the metric flag`() {
        assertEquals(R.string.unit_meters, LengthFormatter.unitRes(true))
        assertEquals(R.string.unit_feet, LengthFormatter.unitRes(false))
        assertEquals(R.string.unit_meters_spoken, LengthFormatter.spokenUnitRes(true))
        assertEquals(R.string.unit_feet_spoken, LengthFormatter.spokenUnitRes(false))
    }
}
