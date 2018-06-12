package com.android.internal.util.lineageos;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SettingsHelper {
	private static SettingsHelper sInstance;

    private final Context mContext;
    private final Observatory mObservatory;

    private SettingsHelper(Context context) {
        mContext = context;
        mObservatory = new Observatory(context, new Handler());
    }

    public static synchronized SettingsHelper get(Context context) {
        if (sInstance == null) {
            sInstance = new SettingsHelper(context);
        }
        return sInstance;
    }

    public void startWatching(OnSettingsChangeListener listener, Uri... settingsUris) {
        mObservatory.register(listener, settingsUris);
    }

    public void stopWatching(OnSettingsChangeListener listener) {
        mObservatory.unregister(listener);
    }

    public interface OnSettingsChangeListener {
        public void onSettingsChanged(Uri settingsUri);
    }

    private static class Observatory extends ContentObserver {

        private final Map<OnSettingsChangeListener, Set<Uri>> mTriggers = new ArrayMap<>();
        private final List<Uri> mRefs = new ArrayList<>();

        private final Context mContext;
        private final ContentResolver mResolver;

        public Observatory(Context context, Handler handler) {
            super(handler);
            mContext = context;
            mResolver = mContext.getContentResolver();
        }

        public void register(OnSettingsChangeListener listener, Uri... contentUris) {
            synchronized (mRefs) {
                Set<Uri> uris = mTriggers.get(listener);
                if (uris == null) {
                    uris = new ArraySet<Uri>();
                    mTriggers.put(listener, uris);
                }
                for (Uri contentUri : contentUris) {
                    uris.add(contentUri);
                    if (!mRefs.contains(contentUri)) {
                        mResolver.registerContentObserver(contentUri, false, this);
                        listener.onSettingsChanged(null);
                    }
                    mRefs.add(contentUri);
                }
            }
        }

        public void unregister(OnSettingsChangeListener listener) {
            synchronized (mRefs) {
                Set<Uri> uris = mTriggers.remove(listener);
                if (uris != null) {
                    for (Uri uri : uris) {
                        mRefs.remove(uri);
                    }
                }
                if (mRefs.size() == 0) {
                    mResolver.unregisterContentObserver(this);
                }
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mRefs) {
                super.onChange(selfChange, uri);

                final Set<OnSettingsChangeListener> notify = new ArraySet<>();
                for (Map.Entry<OnSettingsChangeListener, Set<Uri>> entry : mTriggers.entrySet()) {
                    if (entry.getValue().contains(uri)) {
                        notify.add(entry.getKey());
                    }
                }

                for (OnSettingsChangeListener listener : notify) {
                    listener.onSettingsChanged(uri);
                }
            }
        }
    }
}