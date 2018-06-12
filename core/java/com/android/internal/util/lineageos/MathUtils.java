package com.android.internal.util.lineageos;

public final class MathUtils {

	public static double powerCurveToLinear(final double[] curve, double value) {
        return Math.log((value - curve[0]) / curve[1]) / curve[2];
    }

    public static double linearToPowerCurve(final double[] curve, double value) {
        return curve[0] + curve[1] * Math.exp(curve[2] * value);
    }

    public static double[] powerCurve(double lower, double mid, double upper) {
        final double[] curve = new double[3];
        curve[0] = ((lower * upper) - (mid * mid)) / (lower - (2 * mid) + upper);
        curve[1] = Math.pow((mid - lower), 2) / (lower - (2 * mid) + upper);
        curve[2] = 2 * Math.log((upper - mid) / (mid - lower));
        return curve;
    }

}
