package io.github.viacheslavmezentsev.hwlivetest

import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import io.github.viacheslavmezentsev.hwlivetest.firmware.RttImageBuilder
import kotlin.math.roundToInt

/** ТЗ RTT 3.3: same editor in settings and chart, method-specific supported rates. */
@Composable
fun RecordingRateSlider(rtt: Boolean, hz: Int, onChange: (Int) -> Unit, onFinished: () -> Unit = {}) {
    val rates = if (rtt) RttImageBuilder.sampleRates else (PollingRate.MIN_HZ..PollingRate.MAX_HZ).toList()
    Slider(value = rates.indexOf(hz).coerceAtLeast(0).toFloat(),
        onValueChange = { onChange(rates[it.roundToInt().coerceIn(rates.indices)]) },
        valueRange = 0f..rates.lastIndex.toFloat(), steps = rates.size - 2,
        onValueChangeFinished = onFinished)
}
