package com.company.dlms.agent.profile;

import com.company.dlms.domain.profile.FormattedValue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScalerUnitTest {

    @Test
    void apply_negativeScaler_formatsDecimalValue() {
        FormattedValue formatted = ScalerUnit.apply(12345L, -3, "Wh");

        assertThat(formatted.displayString()).isEqualTo("12.345 Wh");
    }

    @Test
    void apply_zeroScaler_keepsSingleDecimalPlace() {
        FormattedValue formatted = ScalerUnit.apply(100L, 0, "V");

        assertThat(formatted.displayString()).isEqualTo("100.0 V");
    }

    @Test
    void apply_positiveScaler_formatsExpandedValue() {
        FormattedValue formatted = ScalerUnit.apply(5L, 3, "W");

        assertThat(formatted.displayString()).isEqualTo("5000.0 W");
    }

    @Test
    void apply_nullScaler_returnsRawValue() {
        FormattedValue formatted = ScalerUnit.apply(42L, null, "A");

        assertThat(formatted.displayString()).isEqualTo("42 A");
        assertThat(formatted.scaledValue()).isNull();
    }

    @Test
    void apply_nullUnit_omitsSuffix() {
        FormattedValue formatted = ScalerUnit.apply(12345L, -3, null);

        assertThat(formatted.displayString()).isEqualTo("12.345");
    }

    @Test
    void apply_extremeScaler_usesScientificNotation() {
        FormattedValue formatted = ScalerUnit.apply(1L, 7, "Wh");

        assertThat(formatted.displayString()).startsWith("1E7");
    }
}
