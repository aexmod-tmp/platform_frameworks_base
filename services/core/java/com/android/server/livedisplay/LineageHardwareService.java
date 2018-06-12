package com.android.server.livedisplay;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Range;

import com.android.server.SystemService;
import com.android.internal.R;

import com.github.aexmod.ContextConstants;
import com.github.aexmod.livedisplay.ILineageHardwareService;
import com.github.aexmod.livedisplay.LineageHardwareManager;
import com.github.aexmod.livedisplay.DisplayMode;
import com.github.aexmod.livedisplay.HSIC;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.lineageos.hardware.AdaptiveBacklight;
import org.lineageos.hardware.AutoContrast;
import org.lineageos.hardware.ColorBalance;
import org.lineageos.hardware.ColorEnhancement;
import org.lineageos.hardware.DisplayColorCalibration;
import org.lineageos.hardware.DisplayGammaCalibration;
import org.lineageos.hardware.DisplayModeControl;
import org.lineageos.hardware.PictureAdjustment;
import org.lineageos.hardware.SunlightEnhancement;
import org.lineageos.hardware.ReadingEnhancement;

/** @hide */
public class LineageHardwareService extends SystemService {

    private static final boolean DEBUG = true;
    private static final String TAG = LineageHardwareService.class.getSimpleName();

    private final Context mContext;
    private final LineageHardwareInterface mLineageHwImpl;

    private final ArrayMap<String, String> mDisplayModeMappings =
            new ArrayMap<String, String>();
    private final boolean mFilterDisplayModes;

    private interface LineageHardwareInterface {
        public int getSupportedFeatures();
        public boolean get(int feature);
        public boolean set(int feature, boolean enable);

        public int[] getDisplayColorCalibration();
        public boolean setDisplayColorCalibration(int[] rgb);

        public int getNumGammaControls();
        public int[] getDisplayGammaCalibration(int idx);
        public boolean setDisplayGammaCalibration(int idx, int[] rgb);

        public boolean requireAdaptiveBacklightForSunlightEnhancement();
        public boolean isSunlightEnhancementSelfManaged();

        public DisplayMode[] getDisplayModes();
        public DisplayMode getCurrentDisplayMode();
        public DisplayMode getDefaultDisplayMode();
        public boolean setDisplayMode(DisplayMode mode, boolean makeDefault);

        public int getColorBalanceMin();
        public int getColorBalanceMax();
        public int getColorBalance();
        public boolean setColorBalance(int value);

        public HSIC getPictureAdjustment();
        public HSIC getDefaultPictureAdjustment();
        public boolean setPictureAdjustment(HSIC hsic);
        public List<Range<Float>> getPictureAdjustmentRanges();

        public boolean setGrayscale(boolean state);
    }

    private class LegacyLineageHardware implements LineageHardwareInterface {

        private int mSupportedFeatures = 0;

        public LegacyLineageHardware() {
            if (AdaptiveBacklight.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_ADAPTIVE_BACKLIGHT;
            if (ColorEnhancement.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_COLOR_ENHANCEMENT;
            if (DisplayColorCalibration.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_DISPLAY_COLOR_CALIBRATION;
            if (DisplayGammaCalibration.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_DISPLAY_GAMMA_CALIBRATION;
            if (SunlightEnhancement.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT;
            if (AutoContrast.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_AUTO_CONTRAST;
            if (DisplayModeControl.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_DISPLAY_MODES;
            if (ColorBalance.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_COLOR_BALANCE;
            if (PictureAdjustment.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_PICTURE_ADJUSTMENT;
        }

        public int getSupportedFeatures() {
            return mSupportedFeatures;
        }

        public boolean get(int feature) {
            switch(feature) {
                case LineageHardwareManager.FEATURE_ADAPTIVE_BACKLIGHT:
                    return AdaptiveBacklight.isEnabled();
                case LineageHardwareManager.FEATURE_COLOR_ENHANCEMENT:
                    return ColorEnhancement.isEnabled();
                case LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT:
                    return SunlightEnhancement.isEnabled();
                case LineageHardwareManager.FEATURE_AUTO_CONTRAST:
                    return AutoContrast.isEnabled();
                default:
                    Log.e(TAG, "feature " + feature + " is not a boolean feature");
                    return false;
            }
        }

        public boolean set(int feature, boolean enable) {
            switch(feature) {
                case LineageHardwareManager.FEATURE_ADAPTIVE_BACKLIGHT:
                    return AdaptiveBacklight.setEnabled(enable);
                case LineageHardwareManager.FEATURE_COLOR_ENHANCEMENT:
                    return ColorEnhancement.setEnabled(enable);
                case LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT:
                    return SunlightEnhancement.setEnabled(enable);
                case LineageHardwareManager.FEATURE_AUTO_CONTRAST:
                    return AutoContrast.setEnabled(enable);
                default:
                    Log.e(TAG, "feature " + feature + " is not a boolean feature");
                    return false;
            }
        }

        private int[] splitStringToInt(String input, String delimiter) {
            if (input == null || delimiter == null) {
                return null;
            }
            String strArray[] = input.split(delimiter);
            try {
                int intArray[] = new int[strArray.length];
                for(int i = 0; i < strArray.length; i++) {
                    intArray[i] = Integer.parseInt(strArray[i]);
                }
                return intArray;
            } catch (NumberFormatException e) {
                /* ignore */
            }
            return null;
        }

        private String rgbToString(int[] rgb) {
            StringBuilder builder = new StringBuilder();
            builder.append(rgb[LineageHardwareManager.COLOR_CALIBRATION_RED_INDEX]);
            builder.append(" ");
            builder.append(rgb[LineageHardwareManager.COLOR_CALIBRATION_GREEN_INDEX]);
            builder.append(" ");
            builder.append(rgb[LineageHardwareManager.COLOR_CALIBRATION_BLUE_INDEX]);
            return builder.toString();
        }

        public int[] getDisplayColorCalibration() {
            int[] rgb = splitStringToInt(DisplayColorCalibration.getCurColors(), " ");
            if (rgb == null || rgb.length != 3) {
                Log.e(TAG, "Invalid color calibration string");
                return null;
            }
            int[] currentCalibration = new int[6];
            currentCalibration[LineageHardwareManager.COLOR_CALIBRATION_RED_INDEX] = rgb[0];
            currentCalibration[LineageHardwareManager.COLOR_CALIBRATION_GREEN_INDEX] = rgb[1];
            currentCalibration[LineageHardwareManager.COLOR_CALIBRATION_BLUE_INDEX] = rgb[2];
            currentCalibration[LineageHardwareManager.COLOR_CALIBRATION_DEFAULT_INDEX] =
                DisplayColorCalibration.getDefValue();
            currentCalibration[LineageHardwareManager.COLOR_CALIBRATION_MIN_INDEX] =
                DisplayColorCalibration.getMinValue();
            currentCalibration[LineageHardwareManager.COLOR_CALIBRATION_MAX_INDEX] =
                DisplayColorCalibration.getMaxValue();
            return currentCalibration;
        }

        public boolean setDisplayColorCalibration(int[] rgb) {
            return DisplayColorCalibration.setColors(rgbToString(rgb));
        }

        public int getNumGammaControls() {
            return DisplayGammaCalibration.getNumberOfControls();
        }

        public int[] getDisplayGammaCalibration(int idx) {
            int[] rgb = splitStringToInt(DisplayGammaCalibration.getCurGamma(idx), " ");
            if (rgb == null || rgb.length != 3) {
                Log.e(TAG, "Invalid gamma calibration string");
                return null;
            }
            int[] currentCalibration = new int[5];
            currentCalibration[LineageHardwareManager.GAMMA_CALIBRATION_RED_INDEX] = rgb[0];
            currentCalibration[LineageHardwareManager.GAMMA_CALIBRATION_GREEN_INDEX] = rgb[1];
            currentCalibration[LineageHardwareManager.GAMMA_CALIBRATION_BLUE_INDEX] = rgb[2];
            currentCalibration[LineageHardwareManager.GAMMA_CALIBRATION_MIN_INDEX] =
                DisplayGammaCalibration.getMinValue(idx);
            currentCalibration[LineageHardwareManager.GAMMA_CALIBRATION_MAX_INDEX] =
                DisplayGammaCalibration.getMaxValue(idx);
            return currentCalibration;
        }

        public boolean setDisplayGammaCalibration(int idx, int[] rgb) {
            return DisplayGammaCalibration.setGamma(idx, rgbToString(rgb));
        }

        public boolean requireAdaptiveBacklightForSunlightEnhancement() {
            return SunlightEnhancement.isAdaptiveBacklightRequired();
        }

        public boolean isSunlightEnhancementSelfManaged() {
            return SunlightEnhancement.isSelfManaged();
        }

        public DisplayMode[] getDisplayModes() {
            return DisplayModeControl.getAvailableModes();
        }

        public DisplayMode getCurrentDisplayMode() {
            return DisplayModeControl.getCurrentMode();
        }

        public DisplayMode getDefaultDisplayMode() {
            return DisplayModeControl.getDefaultMode();
        }

        public boolean setDisplayMode(DisplayMode mode, boolean makeDefault) {
            return DisplayModeControl.setMode(mode, makeDefault);
        }

        public int getColorBalanceMin() {
            return ColorBalance.getMinValue();
        }

        public int getColorBalanceMax() {
            return ColorBalance.getMaxValue();
        }

        public int getColorBalance() {
            return ColorBalance.getValue();
        }

        public boolean setColorBalance(int value) {
            return ColorBalance.setValue(value);
        }

        public HSIC getPictureAdjustment() { return PictureAdjustment.getHSIC(); }

        public HSIC getDefaultPictureAdjustment() { return PictureAdjustment.getDefaultHSIC(); }

        public boolean setPictureAdjustment(HSIC hsic) { return PictureAdjustment.setHSIC(hsic); }

        public List<Range<Float>> getPictureAdjustmentRanges() {
            return Arrays.asList(
                    PictureAdjustment.getHueRange(),
                    PictureAdjustment.getSaturationRange(),
                    PictureAdjustment.getIntensityRange(),
                    PictureAdjustment.getContrastRange(),
                    PictureAdjustment.getSaturationThresholdRange());
        }

        public boolean setGrayscale(boolean state) {
            return ReadingEnhancement.setGrayscale(state);
        }
    }

    private LineageHardwareInterface getImpl(Context context) {
        return new LegacyLineageHardware();
    }

    public LineageHardwareService(Context context) {
        super(context);
        mContext = context;
        mLineageHwImpl = getImpl(context);
        publishBinderService(ContextConstants.LINEAGE_HARDWARE_SERVICE, mService);

        final String[] mappings = mContext.getResources().getStringArray(
                R.array.config_displayModeMappings);
        if (mappings != null && mappings.length > 0) {
            for (String mapping : mappings) {
                String[] split = mapping.split(":");
                if (split.length == 2) {
                    mDisplayModeMappings.put(split[0], split[1]);
                }
            }
        }
        mFilterDisplayModes = mContext.getResources().getBoolean(
                R.bool.config_filterDisplayModes);
    }

    @Override
    public void onBootPhase(int phase) {
        /*if (phase == PHASE_BOOT_COMPLETED) {
            Intent intent = new Intent("lineageos.intent.action.INITIALIZE_LINEAGE_HARDWARE");
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS);
        }*/
    }

    @Override
    public void onStart() {
    }

    private DisplayMode remapDisplayMode(DisplayMode in) {
        if (in == null) {
            return null;
        }
        if (mDisplayModeMappings.containsKey(in.name)) {
            return new DisplayMode(in.id, mDisplayModeMappings.get(in.name));
        }
        if (!mFilterDisplayModes) {
            return in;
        }
        return null;
    }

    private final IBinder mService = new ILineageHardwareService.Stub() {

        private boolean isSupported(int feature) {
            return (getSupportedFeatures() & feature) == feature;
        }

        @Override
        public int getSupportedFeatures() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            return mLineageHwImpl.getSupportedFeatures();
        }

        @Override
        public boolean get(int feature) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(feature)) {
                Log.e(TAG, "feature " + feature + " is not supported");
                return false;
            }
            return mLineageHwImpl.get(feature);
        }

        @Override
        public boolean set(int feature, boolean enable) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(feature)) {
                Log.e(TAG, "feature " + feature + " is not supported");
                return false;
            }
            return mLineageHwImpl.set(feature, enable);
        }

        @Override
        public int[] getDisplayColorCalibration() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_COLOR_CALIBRATION)) {
                Log.e(TAG, "Display color calibration is not supported");
                return null;
            }
            return mLineageHwImpl.getDisplayColorCalibration();
        }

        @Override
        public boolean setDisplayColorCalibration(int[] rgb) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_COLOR_CALIBRATION)) {
                Log.e(TAG, "Display color calibration is not supported");
                return false;
            }
            if (rgb.length < 3) {
                Log.e(TAG, "Invalid color calibration");
                return false;
            }
            return mLineageHwImpl.setDisplayColorCalibration(rgb);
        }

        @Override
        public int getNumGammaControls() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_GAMMA_CALIBRATION)) {
                Log.e(TAG, "Display gamma calibration is not supported");
                return 0;
            }
            return mLineageHwImpl.getNumGammaControls();
        }

        @Override
        public int[] getDisplayGammaCalibration(int idx) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_GAMMA_CALIBRATION)) {
                Log.e(TAG, "Display gamma calibration is not supported");
                return null;
            }
            return mLineageHwImpl.getDisplayGammaCalibration(idx);
        }

        @Override
        public boolean setDisplayGammaCalibration(int idx, int[] rgb) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_GAMMA_CALIBRATION)) {
                Log.e(TAG, "Display gamma calibration is not supported");
                return false;
            }
            return mLineageHwImpl.setDisplayGammaCalibration(idx, rgb);
        }

        @Override
        public boolean requireAdaptiveBacklightForSunlightEnhancement() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT)) {
                Log.e(TAG, "Sunlight enhancement is not supported");
                return false;
            }
            return mLineageHwImpl.requireAdaptiveBacklightForSunlightEnhancement();
        }

        @Override
        public boolean isSunlightEnhancementSelfManaged() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT)) {
                Log.e(TAG, "Sunlight enhancement is not supported");
                return false;
            }
            return mLineageHwImpl.isSunlightEnhancementSelfManaged();
        }

        @Override
        public DisplayMode[] getDisplayModes() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_MODES)) {
                Log.e(TAG, "Display modes are not supported");
                return null;
            }
            final DisplayMode[] modes = mLineageHwImpl.getDisplayModes();
            if (modes == null) {
                return null;
            }
            final ArrayList<DisplayMode> remapped = new ArrayList<DisplayMode>();
            for (DisplayMode mode : modes) {
                DisplayMode r = remapDisplayMode(mode);
                if (r != null) {
                    remapped.add(r);
                }
            }
            return remapped.toArray(new DisplayMode[remapped.size()]);
        }

        @Override
        public DisplayMode getCurrentDisplayMode() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_MODES)) {
                Log.e(TAG, "Display modes are not supported");
                return null;
            }
            return remapDisplayMode(mLineageHwImpl.getCurrentDisplayMode());
        }

        @Override
        public DisplayMode getDefaultDisplayMode() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_MODES)) {
                Log.e(TAG, "Display modes are not supported");
                return null;
            }
            return remapDisplayMode(mLineageHwImpl.getDefaultDisplayMode());
        }

        @Override
        public boolean setDisplayMode(DisplayMode mode, boolean makeDefault) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_MODES)) {
                Log.e(TAG, "Display modes are not supported");
                return false;
            }
            return mLineageHwImpl.setDisplayMode(mode, makeDefault);
        }

        @Override
        public int getColorBalanceMin() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (isSupported(LineageHardwareManager.FEATURE_COLOR_BALANCE)) {
                return mLineageHwImpl.getColorBalanceMin();
            }
            return 0;
        }

        @Override
        public int getColorBalanceMax() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (isSupported(LineageHardwareManager.FEATURE_COLOR_BALANCE)) {
                return mLineageHwImpl.getColorBalanceMax();
            }
            return 0;
        }

        @Override
        public int getColorBalance() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (isSupported(LineageHardwareManager.FEATURE_COLOR_BALANCE)) {
                return mLineageHwImpl.getColorBalance();
            }
            return 0;
        }

        @Override
        public boolean setColorBalance(int value) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (isSupported(LineageHardwareManager.FEATURE_COLOR_BALANCE)) {
                return mLineageHwImpl.setColorBalance(value);
            }
            return false;
        }

        @Override
        public HSIC getPictureAdjustment() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (isSupported(LineageHardwareManager.FEATURE_PICTURE_ADJUSTMENT)) {
                return mLineageHwImpl.getPictureAdjustment();
            }
            return new HSIC(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        @Override
        public HSIC getDefaultPictureAdjustment() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (isSupported(LineageHardwareManager.FEATURE_PICTURE_ADJUSTMENT)) {
                return mLineageHwImpl.getDefaultPictureAdjustment();
            }
            return new HSIC(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        @Override
        public boolean setPictureAdjustment(HSIC hsic) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (isSupported(LineageHardwareManager.FEATURE_PICTURE_ADJUSTMENT) && hsic != null) {
                return mLineageHwImpl.setPictureAdjustment(hsic);
            }
            return false;
        }

        @Override
        public float[] getPictureAdjustmentRanges() {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (isSupported(LineageHardwareManager.FEATURE_COLOR_BALANCE)) {
                final List<Range<Float>> r = mLineageHwImpl.getPictureAdjustmentRanges();
                return new float[] {
                        r.get(0).getLower(), r.get(0).getUpper(),
                        r.get(1).getLower(), r.get(1).getUpper(),
                        r.get(2).getLower(), r.get(2).getUpper(),
                        r.get(3).getLower(), r.get(3).getUpper(),
                        r.get(4).getUpper(), r.get(4).getUpper() };
            }
            return new float[10];
        }

        @Override
        public boolean setGrayscale(boolean state) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.HARDWARE_ABSTRACTION_ACCESS, null);
            if (!isSupported(LineageHardwareManager.FEATURE_READING_ENHANCEMENT)) {
                Log.e(TAG, "Reading enhancement not supported");
                return false;
            }
            return mLineageHwImpl.setGrayscale(state);
        }
    };
}