/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.SurfaceTexture;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import dalvik.system.CloseGuard;

import java.lang.ref.WeakReference;

/** @hide */
public class ActivityView extends ViewGroup {
    private static final String TAG = "ActivityView";
    private static final boolean DEBUG = false;

    DisplayMetrics mMetrics;
    private final TextureView mTextureView;
    private IActivityContainer mActivityContainer;
    private Activity mActivity;
    private int mWidth;
    private int mHeight;
    private Surface mSurface;

    // Only one IIntentSender or Intent may be queued at a time. Most recent one wins.
    IIntentSender mQueuedPendingIntent;
    Intent mQueuedIntent;

    private final CloseGuard mGuard = CloseGuard.get();

    public ActivityView(Context context) {
        this(context, null);
    }

    public ActivityView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ActivityView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                mActivity = (Activity)context;
                break;
            }
            context = ((ContextWrapper)context).getBaseContext();
        }
        if (mActivity == null) {
            throw new IllegalStateException("The ActivityView's Context is not an Activity.");
        }

        try {
            mActivityContainer = ActivityManagerNative.getDefault().createActivityContainer(
                    mActivity.getActivityToken(), new ActivityContainerCallback(this));
        } catch (RemoteException e) {
            throw new IllegalStateException("ActivityView: Unable to create ActivityContainer. "
                    + e);
        }

        mTextureView = new TextureView(context);
        mTextureView.setSurfaceTextureListener(new ActivityViewSurfaceTextureListener());
        addView(mTextureView);

        WindowManager wm = (WindowManager)mActivity.getSystemService(Context.WINDOW_SERVICE);
        mMetrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(mMetrics);

        mGuard.open("release");

        if (DEBUG) Log.v(TAG, "ctor()");
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mTextureView.layout(0, 0, r - l, b - t);
    }

    private boolean injectInputEvent(InputEvent event) {
        try {
            return mActivityContainer != null && mActivityContainer.injectEvent(event);
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return injectInputEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (injectInputEvent(event)) {
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    public boolean isAttachedToDisplay() {
        return mSurface != null;
    }

    public void startActivity(Intent intent) {
        if (mActivityContainer == null) {
            throw new IllegalStateException("Attempt to call startActivity after release");
        }
        if (DEBUG) Log.v(TAG, "startActivity(): intent=" + intent + " " +
                (isAttachedToDisplay() ? "" : "not") + " attached");
        if (mSurface != null) {
            try {
                mActivityContainer.startActivity(intent);
            } catch (RemoteException e) {
                throw new IllegalStateException("ActivityView: Unable to startActivity. " + e);
            }
        } else {
            mQueuedIntent = intent;
            mQueuedPendingIntent = null;
        }
    }

    private void startActivityIntentSender(IIntentSender iIntentSender) {
        try {
            mActivityContainer.startActivityIntentSender(iIntentSender);
        } catch (RemoteException e) {
            throw new IllegalStateException(
                    "ActivityView: Unable to startActivity from IntentSender. " + e);
        }
    }

    public void startActivity(IntentSender intentSender) {
        if (mActivityContainer == null) {
            throw new IllegalStateException("Attempt to call startActivity after release");
        }
        if (DEBUG) Log.v(TAG, "startActivityIntentSender(): intentSender=" + intentSender + " " +
                (isAttachedToDisplay() ? "" : "not") + " attached");
        final IIntentSender iIntentSender = intentSender.getTarget();
        if (mSurface != null) {
            startActivityIntentSender(iIntentSender);
        } else {
            mQueuedPendingIntent = iIntentSender;
            mQueuedIntent = null;
        }
    }

    public void startActivity(PendingIntent pendingIntent) {
        if (mActivityContainer == null) {
            throw new IllegalStateException("Attempt to call startActivity after release");
        }
        if (DEBUG) Log.v(TAG, "startActivityPendingIntent(): PendingIntent=" + pendingIntent + " "
                + (isAttachedToDisplay() ? "" : "not") + " attached");
        final IIntentSender iIntentSender = pendingIntent.getTarget();
        if (mSurface != null) {
            startActivityIntentSender(iIntentSender);
        } else {
            mQueuedPendingIntent = iIntentSender;
            mQueuedIntent = null;
        }
    }

    public void release() {
        if (DEBUG) Log.v(TAG, "release()");
        if (mActivityContainer == null) {
            Log.e(TAG, "Duplicate call to release");
            return;
        }
        try {
            mActivityContainer.release();
        } catch (RemoteException e) {
        }
        mActivityContainer = null;

        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }

        mTextureView.setSurfaceTextureListener(null);

        mGuard.close();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mGuard != null) {
                mGuard.warnIfOpen();
                release();
            }
        } finally {
            super.finalize();
        }
    }

    private void attachToSurfaceWhenReady() {
        final SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        if (surfaceTexture == null || mSurface != null) {
            // Either not ready to attach, or already attached.
            return;
        }

        mSurface = new Surface(surfaceTexture);
        try {
            mActivityContainer.setSurface(mSurface, mWidth, mHeight, mMetrics.densityDpi);
        } catch (RemoteException e) {
            mSurface.release();
            mSurface = null;
            throw new RuntimeException(
                    "ActivityView: Unable to create ActivityContainer. " + e);
        }

        if (DEBUG) Log.v(TAG, "attachToSurfaceWhenReady: " + (mQueuedIntent != null ||
                mQueuedPendingIntent != null ? "" : "no") + " queued intent");
        if (mQueuedIntent != null) {
            startActivity(mQueuedIntent);
            mQueuedIntent = null;
        } else if (mQueuedPendingIntent != null) {
            startActivityIntentSender(mQueuedPendingIntent);
            mQueuedPendingIntent = null;
        }
    }

    private class ActivityViewSurfaceTextureListener implements SurfaceTextureListener {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width,
                int height) {
            if (mActivityContainer == null) {
                return;
            }
            if (DEBUG) Log.d(TAG, "onSurfaceTextureAvailable: width=" + width + " height="
                    + height);
            mWidth = width;
            mHeight = height;
            attachToSurfaceWhenReady();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width,
                int height) {
            if (mActivityContainer == null) {
                return;
            }
            if (DEBUG) Log.d(TAG, "onSurfaceTextureSizeChanged: w=" + width + " h=" + height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            if (mActivityContainer == null) {
                return true;
            }
            if (DEBUG) Log.d(TAG, "onSurfaceTextureDestroyed");
            mSurface.release();
            mSurface = null;
            try {
                mActivityContainer.setSurface(null, mWidth, mHeight, mMetrics.densityDpi);
            } catch (RemoteException e) {
                throw new RuntimeException(
                        "ActivityView: Unable to set surface of ActivityContainer. " + e);
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
//            Log.d(TAG, "onSurfaceTextureUpdated");
        }

    }

    private static class ActivityContainerCallback extends IActivityContainerCallback.Stub {
        private final WeakReference<ActivityView> mActivityViewWeakReference;

        ActivityContainerCallback(ActivityView activityView) {
            mActivityViewWeakReference = new WeakReference<ActivityView>(activityView);
        }

        @Override
        public void setVisible(IBinder container, boolean visible) {
            if (DEBUG) Log.v(TAG, "setVisible(): container=" + container + " visible=" + visible +
                    " ActivityView=" + mActivityViewWeakReference.get());
        }
    }
}
