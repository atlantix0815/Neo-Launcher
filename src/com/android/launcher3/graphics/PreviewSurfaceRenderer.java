/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.launcher3.graphics;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;

import android.app.WallpaperColors;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.database.Cursor;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceControlViewHost.SurfacePackage;
import android.view.View;
import android.view.WindowManager.LayoutParams;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.Utilities;
import com.android.launcher3.Workspace;
import com.android.launcher3.graphics.LauncherPreviewRenderer.PreviewContext;
import com.android.launcher3.model.BgDataModel;
import com.android.launcher3.model.GridSizeMigrationUtil;
import com.android.launcher3.model.LoaderTask;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.util.RunnableList;
import com.android.launcher3.util.Themes;
import com.android.launcher3.widget.LocalColorExtractor;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Render preview using surface view. */
@SuppressWarnings("NewApi")
public class PreviewSurfaceRenderer {

    private static final String TAG = "PreviewSurfaceRenderer";

    private static final int FADE_IN_ANIMATION_DURATION = 200;

    private static final String KEY_HOST_TOKEN = "host_token";
    private static final String KEY_VIEW_WIDTH = "width";
    private static final String KEY_VIEW_HEIGHT = "height";
    private static final String KEY_DISPLAY_ID = "display_id";
    private static final String KEY_COLORS = "wallpaper_colors";

    private final Context mContext;
    private final InvariantDeviceProfile mIdp;
    private final IBinder mHostToken;
    private final int mWidth;
    private final int mHeight;
    private final Display mDisplay;
    private final WallpaperColors mWallpaperColors;
    private final RunnableList mOnDestroyCallbacks = new RunnableList();

    private final SurfaceControlViewHost mSurfaceControlViewHost;

    private boolean mDestroyed = false;
    private LauncherPreviewRenderer mRenderer;
    private boolean mHideQsb;

    public PreviewSurfaceRenderer(Context context, Bundle bundle) throws Exception {
        mContext = context;

        String gridName = bundle.getString("name");
        bundle.remove("name");
        if (gridName == null) {
            gridName = InvariantDeviceProfile.getCurrentGridName(context);
        }
        mWallpaperColors = bundle.getParcelable(KEY_COLORS);
        mHideQsb = bundle.getBoolean(GridCustomizationsProvider.KEY_HIDE_BOTTOM_ROW);
        mIdp = new InvariantDeviceProfile(context, gridName);

        mHostToken = bundle.getBinder(KEY_HOST_TOKEN);
        mWidth = bundle.getInt(KEY_VIEW_WIDTH);
        mHeight = bundle.getInt(KEY_VIEW_HEIGHT);
        mDisplay = context.getSystemService(DisplayManager.class)
                .getDisplay(bundle.getInt(KEY_DISPLAY_ID));

        mSurfaceControlViewHost = MAIN_EXECUTOR
                .submit(() -> new SurfaceControlViewHost(mContext, mDisplay, mHostToken))
                .get(5, TimeUnit.SECONDS);
        mOnDestroyCallbacks.add(mSurfaceControlViewHost::release);
    }

    public IBinder getHostToken() {
        return mHostToken;
    }

    public SurfacePackage getSurfacePackage() {
        return mSurfaceControlViewHost.getSurfacePackage();
    }

    /**
     * Destroys the preview and all associated data
     */
    @UiThread
    public void destroy() {
        mDestroyed = true;
        mOnDestroyCallbacks.executeAllAndDestroy();
    }

    /**
     * A function that queries for the launcher app widget span info
     *
     * @param context The context to get the content resolver from, should be related to launcher
     * @return A SparseArray with the app widget id being the key and the span info being the values
     */
    @WorkerThread
    @Nullable
    public SparseArray<Size> getLoadedLauncherWidgetInfo(
            @NonNull final Context context) {
        final SparseArray<Size> widgetInfo = new SparseArray<>();
        final String query = LauncherSettings.Favorites.ITEM_TYPE + " = "
                + LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET;

        try (Cursor c = context.getContentResolver().query(LauncherSettings.Favorites.CONTENT_URI,
                new String[] {
                        LauncherSettings.Favorites.APPWIDGET_ID,
                        LauncherSettings.Favorites.SPANX,
                        LauncherSettings.Favorites.SPANY
                }, query, null, null)) {
            final int appWidgetIdIndex = c.getColumnIndexOrThrow(
                    LauncherSettings.Favorites.APPWIDGET_ID);
            final int spanXIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SPANX);
            final int spanYIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SPANY);
            while (c.moveToNext()) {
                final int appWidgetId = c.getInt(appWidgetIdIndex);
                final int spanX = c.getInt(spanXIndex);
                final int spanY = c.getInt(spanYIndex);

                widgetInfo.append(appWidgetId, new Size(spanX, spanY));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying for launcher widget info", e);
            return null;
        }

        return widgetInfo;
    }

    /**
     * Generates the preview in background
     */
    public void loadAsync() {
        MODEL_EXECUTOR.execute(this::loadModelData);
    }

    /**
     * Hides the components in the bottom row.
     *
     * @param hide True to hide and false to show.
     */
    public void hideBottomRow(boolean hide) {
        if (mRenderer != null) {
            mRenderer.hideBottomRow(hide);
        }
    }

    @WorkerThread
    private void loadModelData() {
        final boolean migrated = doGridMigrationIfNecessary();

        final Context inflationContext;
        if (mWallpaperColors != null) {
            // Create a themed context, without affecting the main application context
            Context context = mContext.createDisplayContext(mDisplay);
            if (Utilities.ATLEAST_R) {
                context = context.createWindowContext(
                        LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            }
            LocalColorExtractor.newInstance(mContext)
                    .applyColorsOverride(context, mWallpaperColors);
            inflationContext = new ContextThemeWrapper(context,
                    Themes.getActivityThemeRes(context, mWallpaperColors.getColorHints()));
        } else {
            inflationContext = new ContextThemeWrapper(mContext,
                    Themes.getActivityThemeRes(mContext));
        }

        if (migrated) {
            PreviewContext previewContext = new PreviewContext(inflationContext, mIdp);
            new LoaderTask(
                    LauncherAppState.getInstance(previewContext),
                    /* bgAllAppsList= */ null,
                    new BgDataModel(),
                    LauncherAppState.getInstance(previewContext).getModel().getModelDelegate(),
                    /* results= */ null) {

                @Override
                public void run() {
                    DeviceProfile deviceProfile = mIdp.getDeviceProfile(previewContext);
                    String query =
                            LauncherSettings.Favorites.SCREEN + " = " + Workspace.FIRST_SCREEN_ID
                                    + " or " + LauncherSettings.Favorites.CONTAINER + " = "
                                    + LauncherSettings.Favorites.CONTAINER_HOTSEAT;
                    if (deviceProfile.isTwoPanels) {
                        query += " or " + LauncherSettings.Favorites.SCREEN + " = "
                                + Workspace.SECOND_SCREEN_ID;
                    }
                    loadWorkspace(new ArrayList<>(), LauncherSettings.Favorites.PREVIEW_CONTENT_URI,
                            query);

                    final SparseArray<Size> spanInfo =
                            getLoadedLauncherWidgetInfo(previewContext.getBaseContext());

                    MAIN_EXECUTOR.execute(() -> {
                        renderView(previewContext, mBgDataModel, mWidgetProvidersMap, spanInfo);
                        mOnDestroyCallbacks.add(previewContext::onDestroy);
                    });
                }
            }.run();
        } else {
            LauncherAppState.getInstance(inflationContext).getModel().loadAsync(dataModel -> {
                if (dataModel != null) {
                    MAIN_EXECUTOR.execute(() -> renderView(inflationContext, dataModel, null,
                            null));
                } else {
                    Log.e(TAG, "Model loading failed");
                }
            });
        }
    }

    @WorkerThread
    private boolean doGridMigrationIfNecessary() {
        if (!GridSizeMigrationUtil.needsToMigrate(mContext, mIdp)) {
            return false;
        }
        return GridSizeMigrationUtil.migrateGridIfNeeded(mContext, mIdp);
    }

    @UiThread
    private void renderView(Context inflationContext, BgDataModel dataModel,
                            Map<ComponentKey, AppWidgetProviderInfo> widgetProviderInfoMap,
                            @Nullable final SparseArray<Size> launcherWidgetSpanInfo) {
        if (mDestroyed) {
            return;
        }
        mRenderer = new LauncherPreviewRenderer(inflationContext, mIdp,
                mWallpaperColors, launcherWidgetSpanInfo);
        mRenderer.hideBottomRow(mHideQsb);
        View view = mRenderer.getRenderedView(dataModel, widgetProviderInfoMap);
        // This aspect scales the view to fit in the surface and centers it
        final float scale = Math.min(mWidth / (float) view.getMeasuredWidth(),
                mHeight / (float) view.getMeasuredHeight());
        view.setScaleX(scale);
        view.setScaleY(scale);
        view.setPivotX(0);
        view.setPivotY(0);
        view.setTranslationX((mWidth - scale * view.getWidth()) / 2);
        view.setTranslationY((mHeight - scale * view.getHeight()) / 2);
        view.setAlpha(0);
        view.animate().alpha(1)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setDuration(FADE_IN_ANIMATION_DURATION)
                .start();
        mSurfaceControlViewHost.setView(view, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
}
