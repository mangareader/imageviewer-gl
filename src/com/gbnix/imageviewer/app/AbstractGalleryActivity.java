/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.gbnix.imageviewer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;

import com.gbnix.imageviewer.R;
import com.gbnix.imageviewer.data.DataManager;
import com.gbnix.imageviewer.data.MediaItem;
import com.gbnix.imageviewer.ui.GLRoot;
import com.gbnix.imageviewer.ui.GLRootView;
import com.gbnix.imageviewer.util.ThreadPool;

public abstract class AbstractGalleryActivity extends Activity implements GalleryActivity {
	@SuppressWarnings("unused")
	private static final String TAG = "AbstractGalleryActivity";
	private GLRootView mGLRootView;
	private StateManager mStateManager;
	private OrientationManager mOrientationManager;
	private final TransitionStore mTransitionStore = new TransitionStore();
	private boolean mDisableToggleStatusBar;

	private AlertDialog mAlertDialog = null;
	private final BroadcastReceiver mMountReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			if (getExternalCacheDir() != null) {
				onStorageReady();
			}
		}
	};
	private final IntentFilter mMountFilter = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);

	@Override
	public Context getAndroidContext() {
		return this;
	}

	@Override
	public DataManager getDataManager() {
		return ((GalleryApp) getApplication()).getDataManager();
	}

	@Override
	public GLRoot getGLRoot() {
		return mGLRootView;
	}

	@Override
	public OrientationManager getOrientationManager() {
		return mOrientationManager;
	}

	@Override
	public synchronized StateManager getStateManager() {
		if (mStateManager == null) {
			mStateManager = new StateManager(this);
		}
		return mStateManager;
	}

	@Override
	public ThreadPool getThreadPool() {
		return ((GalleryApp) getApplication()).getThreadPool();
	}

	@Override
	public TransitionStore getTransitionStore() {
		return mTransitionStore;
	}

	@Override
	public void onBackPressed() {
		// send the back event to the top sub-state
		final GLRoot root = getGLRoot();
		root.lockRenderThread();
		try {
			getStateManager().onBackPressed();
		} finally {
			root.unlockRenderThread();
		}
	}

	@Override
	public void onConfigurationChanged(final Configuration config) {
		super.onConfigurationChanged(config);
		mStateManager.onConfigurationChange(config);
		invalidateOptionsMenu();
		toggleStatusBarByOrientation();
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		final GLRoot root = getGLRoot();
		root.lockRenderThread();
		try {
			return getStateManager().itemSelected(item);
		} finally {
			root.unlockRenderThread();
		}
	}

	@Override
	public void setContentView(final int resId) {
		super.setContentView(resId);
		mGLRootView = (GLRootView) findViewById(R.id.gl_root_view);
	}

	protected void disableToggleStatusBar() {
		mDisableToggleStatusBar = true;
	}

	@Override
	protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
		mGLRootView.lockRenderThread();
		try {
			getStateManager().notifyActivityResult(requestCode, resultCode, data);
		} finally {
			mGLRootView.unlockRenderThread();
		}
	}

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		mOrientationManager = new OrientationManager(this);
		toggleStatusBarByOrientation();
		getWindow().setBackgroundDrawable(null);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		mGLRootView.lockRenderThread();
		try {
			getStateManager().destroy();
		} finally {
			mGLRootView.unlockRenderThread();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		mOrientationManager.pause();
		mGLRootView.onPause();
		mGLRootView.lockRenderThread();
		try {
			getStateManager().pause();
			getDataManager().pause();
		} finally {
			mGLRootView.unlockRenderThread();
		}
		MediaItem.getMicroThumbPool().clear();
		MediaItem.getThumbPool().clear();
		MediaItem.getBytesBufferPool().clear();
	}

	@Override
	protected void onResume() {
		super.onResume();
		mGLRootView.lockRenderThread();
		try {
			getStateManager().resume();
			getDataManager().resume();
		} finally {
			mGLRootView.unlockRenderThread();
		}
		mGLRootView.onResume();
		mOrientationManager.resume();
	}

	@Override
	protected void onSaveInstanceState(final Bundle outState) {
		mGLRootView.lockRenderThread();
		try {
			super.onSaveInstanceState(outState);
			getStateManager().saveState(outState);
		} finally {
			mGLRootView.unlockRenderThread();
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (getExternalCacheDir() == null) {
			final OnCancelListener onCancel = new OnCancelListener() {
				@Override
				public void onCancel(final DialogInterface dialog) {
					finish();
				}
			};
			final OnClickListener onClick = new OnClickListener() {
				@Override
				public void onClick(final DialogInterface dialog, final int which) {
					dialog.cancel();
				}
			};
			mAlertDialog = new AlertDialog.Builder(this).setIcon(android.R.drawable.ic_dialog_alert)
					.setTitle("No Storage").setMessage("No external storage available.")
					.setNegativeButton(android.R.string.cancel, onClick).setOnCancelListener(onCancel).show();
			registerReceiver(mMountReceiver, mMountFilter);
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (mAlertDialog != null) {
			unregisterReceiver(mMountReceiver);
			mAlertDialog.dismiss();
			mAlertDialog = null;
		}
	}

	protected void onStorageReady() {
		if (mAlertDialog != null) {
			mAlertDialog.dismiss();
			mAlertDialog = null;
			unregisterReceiver(mMountReceiver);
		}
	}

	// Shows status bar in portrait view, hide in landscape view
	private void toggleStatusBarByOrientation() {
		if (mDisableToggleStatusBar) return;

		final Window win = getWindow();
		if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
			win.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		} else {
			win.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}
}
