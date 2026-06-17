package com.company.dlms.agent.profile;

import com.company.dlms.domain.profile.FormattedValue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;

public final class ScalerUnit {

    private static final BigDecimal DOUBLE_MAX = BigDecimal.valueOf(Double.MAX_VALUE);

    private ScalerUnit() {}

    public static FormattedValue apply(long rawValue, Integer scaler, String unit) {
        return apply(BigDecimal.valueOf(rawValue), scaler, unit);
    }

    public static FormattedValue apply(BigDecimal rawValue, Integer scaler, String unit) {
        if (rawValue == null) {
            return new FormattedValue(null, null, "null", unit);
        }

        if (scaler == null) {
            return new FormattedValue(rawValue, null, appendUnit(toPlain(rawValue), unit), unit);
        }

        BigDecimal scaledValue = rawValue.scaleByPowerOfTen(scaler);
        if (scaledValue.abs().compareTo(DOUBLE_MAX) > 0) {
            return new FormattedValue(rawValue, null, "OVERFLOW", unit);
        }

        String formatted = Math.abs(scaler) > 6
                ? scientific(scaledValue)
                : toScaledPlain(scaledValue, scaler);
        return new FormattedValue(rawValue, scaledValue, appendUnit(formatted, unit), unit);
    }

    private static String toScaledPlain(BigDecimal value, Integer scaler) {
        int scale = scaler != null && scaler < 0 ? -scaler : 1;
        BigDecimal normalized = value.setScale(scale, RoundingMode.HALF_UP);
        return normalized.toPlainString();
    }

    private static String scientific(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("0.############E0");
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value.round(MathContext.DECIMAL64));
    }

    private static String appendUnit(String value, String unit) {
        if (unit == null || unit.isBlank()) {
            return value;
        }
        return value + " " + unit;
    }

    private static String toPlain(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0, RoundingMode.HALF_UP);
        }
        return normalized.toPlainString();
    }
}
