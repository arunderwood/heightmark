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
        assertEquals(112, LengthFormatter.hero(112.44, useMetric = true).value)
        assertEquals(113, LengthFormatter.hero(112.51, useMetric = true).value)
        // kotlin.math.round is half-to-even (rint): a .5 tie goes to the even neighbor
        assertEquals(112, LengthFormatter.hero(112.5, useMetric = true).value)
        assertEquals(114, LengthFormatter.hero(113.5, useMetric = true).value)
    }

    @Test
    fun `imperial hero value converts before rounding`() {
        // 100 m = 328.084 ft
        assertEquals(328, LengthFormatter.hero(100.0, useMetric = false).value)
    }

    @Test
    fun `a metric hero carries the meter unit resources`() {
        val hero = LengthFormatter.hero(112.0, useMetric = true)
        assertEquals(R.string.unit_meters, hero.unitRes)
        assertEquals(R.string.unit_meters_spoken, hero.spokenUnitRes)
    }

    @Test
    fun `an imperial hero carries the feet unit resources`() {
        val hero = LengthFormatter.hero(112.0, useMetric = false)
        assertEquals(R.string.unit_feet, hero.unitRes)
        assertEquals(R.string.unit_feet_spoken, hero.spokenUnitRes)
    }
}
