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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;

import android.app.ActionBar;
import android.app.ActionBar.OnMenuVisibilityListener;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuInflater;

import com.gbnix.imageviewer.R;
import com.gbnix.imageviewer.data.DataManager;
import com.gbnix.imageviewer.data.MediaItem;
import com.gbnix.imageviewer.data.MediaSet;
import com.gbnix.imageviewer.data.Path;
import com.gbnix.imageviewer.ui.GLCanvas;
import com.gbnix.imageviewer.ui.GLView;
import com.gbnix.imageviewer.ui.PhotoView;
import com.gbnix.imageviewer.ui.SynchronizedHandler;
import com.gbnix.imageviewer.util.ArrayUtils;

public class PhotoPage extends ActivityState implements PhotoView.Listener, OrientationManager.Listener {
	private static final String TAG = "PhotoPage";

	private static final int MSG_HIDE_BARS = 1;
	private static final int MSG_LOCK_ORIENTATION = 2;
	private static final int MSG_UNLOCK_ORIENTATION = 3;
	private static final int MSG_ON_FULL_SCREEN_CHANGED = 4;
	private static final int MSG_UPDATE_ACTION_BAR = 5;
	private static final int MSG_UNFREEZE_GLROOT = 6;
	private static final int MSG_WANT_BARS = 7;

	private static final int HIDE_BARS_TIMEOUT = 3500;
	private static final int UNFREEZE_GLROOT_TIMEOUT = 250;

	public static final String KEY_MEDIA_SET_PATH = "media-set-path";
	public static final String KEY_MEDIA_ITEM_PATH = "media-item-path";
	public static final String KEY_INDEX_HINT = "index-hint";
	public static final String KEY_OPEN_ANIMATION_RECT = "open-animation-rect";
	public static final String KEY_APP_BRIDGE = "app-bridge";
	public static final String KEY_TREAT_BACK_AS_UP = "treat-back-as-up";

	public static final String KEY_RETURN_INDEX_HINT = "return-index-hint";

	private PhotoView mPhotoView;
	private PhotoPage.Model mModel;
	private Path mPendingSharePath;

	// mMediaSet could be null if there is no KEY_MEDIA_SET_PATH supplied.
	// E.g., viewing a photo in gmail attachment
	private MediaSet mMediaSet;

	private int mCurrentIndex = 0;
	private Handler mHandler;
	private boolean mShowBars = true;
	private volatile boolean mActionBarAllowed = true;
	private ActionBar mActionBar;
	private MyMenuVisibilityListener mMenuVisibilityListener;
	private boolean mIsMenuVisible;
	private MediaItem mCurrentPhoto = null;
	private boolean mIsActive;
	private String mSetPathString;
	// This is the original mSetPathString before adding the camera preview
	// item.
	private OrientationManager mOrientationManager;

	private final GLView mRootPane = new GLView() {

		@Override
		protected void onLayout(final boolean changed, final int left, final int top, final int right, final int bottom) {
			mPhotoView.layout(0, 0, right - left, bottom - top);
		}

		@Override
		protected void renderBackground(final GLCanvas view) {
			view.clearBuffer();
		}
	};

	@Override
	public void lockOrientation() {
		mHandler.sendEmptyMessage(MSG_LOCK_ORIENTATION);
	}

	@Override
	public void onActionBarAllowed(final boolean allowed) {
		mActionBarAllowed = allowed;
		mHandler.sendEmptyMessage(MSG_UPDATE_ACTION_BAR);
	}

	@Override
	public void onActionBarWanted() {
		mHandler.sendEmptyMessage(MSG_WANT_BARS);
	}

	@Override
	public void onCommitDeleteImage() {
	}

	@Override
	public void onCreate(final Bundle data, final Bundle restoreState) {
		mActionBar = mActivity.getActionBar();

		mPhotoView = new PhotoView(mActivity);
		mPhotoView.setListener(this);
		mRootPane.addComponent(mPhotoView);
		mOrientationManager = mActivity.getOrientationManager();
		mOrientationManager.addListener(this);
		mActivity.getGLRoot().setOrientationSource(mOrientationManager);
		final DataManager dm = mActivity.getDataManager();

		final ArrayList<Uri> uris = data.getParcelableArrayList(Gallery.EXTRA_URIS);

		try {
			final int length = uris.size();
			final String[] uris_encoded = new String[length];
			for (int i = 0; i < length; i++) {
				uris_encoded[i] = URLEncoder.encode(uris.get(i).toString(), "UTF-8");
			}
			final Uri uri = Uri.parse("uri_set://"
					+ Base64.encodeToString(ArrayUtils.toString(uris_encoded, ';', false).getBytes("UTF-8"),
							Base64.DEFAULT));
			mSetPathString = dm.findPathByUri(uri, "image/*").toString();
		} catch (final UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}

		final Path itemPath = dm.getMediaSet(mSetPathString).getMediaItem(0, 1).get(0).getPath();
		mMediaSet = mActivity.getDataManager().getMediaSet(mSetPathString);
		mCurrentIndex = data.getInt(KEY_INDEX_HINT, 0);
		if (mMediaSet == null) {
			Log.w(TAG, "failed to restore " + mSetPathString);
		}
		final PhotoDataAdapter pda = new PhotoDataAdapter(mActivity, mPhotoView, mMediaSet, itemPath, mCurrentIndex);
		mModel = pda;
		mPhotoView.setModel(mModel);

		pda.setDataListener(new PhotoDataAdapter.DataListener() {

			@Override
			public void onLoadingFinished() {
				if (!mModel.isEmpty()) {
					final MediaItem photo = mModel.getMediaItem(0);
					if (photo != null) {
						updateCurrentPhoto(photo);
					}
				} else if (mIsActive) {
					// We only want to finish the PhotoPage if there is no
					// deletion that the user can undo.
				}
			}

			@Override
			public void onLoadingStarted() {
			}

			@Override
			public void onPhotoChanged(final int index, final Path item) {
				mActivity.onPhotoChanged(index, item);
				mCurrentIndex = index;
				if (item != null) {
					final MediaItem photo = mModel.getMediaItem(0);
					if (photo != null) {
						updateCurrentPhoto(photo);
					}
				}
				updateBars();
			}
		});

		mHandler = new SynchronizedHandler(mActivity.getGLRoot()) {
			@Override
			public void handleMessage(final Message message) {
				switch (message.what) {
					case MSG_HIDE_BARS: {
						hideBars();
						break;
					}
					case MSG_LOCK_ORIENTATION: {
						mOrientationManager.lockOrientation();
						break;
					}
					case MSG_UNLOCK_ORIENTATION: {
						mOrientationManager.unlockOrientation();
						break;
					}
					case MSG_ON_FULL_SCREEN_CHANGED: {
						break;
					}
					case MSG_UPDATE_ACTION_BAR: {
						updateBars();
						break;
					}
					case MSG_WANT_BARS: {
						wantBars();
						break;
					}
					case MSG_UNFREEZE_GLROOT: {
						mActivity.getGLRoot().unfreeze();
						break;
					}
					default:
						throw new AssertionError(message.what);
				}
			}
		};

		// start the opening animation only if it's not restored.
		if (restoreState == null) {
			mPhotoView.setOpenAnimationRect((Rect) data.getParcelable(KEY_OPEN_ANIMATION_RECT));
		}
	}

	@Override
	public void onCurrentImageUpdated() {
		mActivity.getGLRoot().unfreeze();
	}

	// ////////////////////////////////////////////////////////////////////////
	// Action Bar show/hide management
	// ////////////////////////////////////////////////////////////////////////

	// How we do delete/undo:
	//
	// When the user choose to delete a media item, we just tell the
	// FilterDeleteSet to hide that item. If the user choose to undo it, we
	// again tell FilterDeleteSet not to hide it. If the user choose to commit
	// the deletion, we then actually delete the media item.
	@Override
	public void onDeleteImage(final Path path, final int offset) {
		onCommitDeleteImage(); // commit the previous deletion
	}

	@Override
	public void onFullScreenChanged(final boolean full) {
		final Message m = mHandler.obtainMessage(MSG_ON_FULL_SCREEN_CHANGED, full ? 1 : 0, 0);
		m.sendToTarget();
	}

	@Override
	public void onOrientationCompensationChanged() {
		mActivity.getGLRoot().requestLayoutContentPane();
	}

	@Override
	public void onPause() {
		super.onPause();
		mIsActive = false;

		mActivity.getGLRoot().unfreeze();
		mHandler.removeMessages(MSG_UNFREEZE_GLROOT);

		mPhotoView.pause();
		mModel.pause();
		mHandler.removeMessages(MSG_HIDE_BARS);
		mActionBar.removeOnMenuVisibilityListener(mMenuVisibilityListener);

		onCommitDeleteImage();
	}

	// //////////////////////////////////////////////////////////////////////////
	// Callbacks from PhotoView
	// //////////////////////////////////////////////////////////////////////////
	@Override
	public void onSingleTapUp(final int x, final int y) {

		final MediaItem item = mModel.getMediaItem(0);
		if (item == null) // item is not ready or it
							// is camera preview,
							// ignore
			return;

		boolean playVideo = (item.getSupportedOperations() & MediaItem.SUPPORT_PLAY) != 0;

		if (playVideo) {
			// determine if the point is at center (1/6) of the photo view.
			// (The position of the "play" icon is at center (1/6) of the photo)
			final int w = mPhotoView.getWidth();
			final int h = mPhotoView.getHeight();
			playVideo = Math.abs(x - w / 2) * 12 <= w && Math.abs(y - h / 2) * 12 <= h;
		}

		if (playVideo) {
			playVideo((Activity) mActivity, item.getPlayUri(), item.getName());
		} else {
			toggleBars();
		}
	}

	@Override
	public void onUndoDeleteImage() {
	}

	@Override
	public void unlockOrientation() {
		mHandler.sendEmptyMessage(MSG_UNLOCK_ORIENTATION);
	}

	// ////////////////////////////////////////////////////////////////////////
	// AppBridge.Server interface
	// ////////////////////////////////////////////////////////////////////////

	@Override
	protected boolean onCreateActionBar(final Menu menu) {
		final MenuInflater inflater = ((Activity) mActivity).getMenuInflater();
		inflater.inflate(R.menu.photo, menu);
		if (mPendingSharePath != null) {
		}
		updateMenuOperations();
		updateTitle();
		return true;
	}

	@Override
	protected void onDestroy() {
		mOrientationManager.removeListener(this);
		mActivity.getGLRoot().setOrientationSource(null);

		// Remove all pending messages.
		mHandler.removeCallbacksAndMessages(null);
		super.onDestroy();
	}

	@Override
	protected void onResume() {
		super.onResume();
		mActivity.getGLRoot().freeze();
		mIsActive = true;
		setContentPane(mRootPane);

		mModel.resume();
		mPhotoView.resume();
		if (mMenuVisibilityListener == null) {
			mMenuVisibilityListener = new MyMenuVisibilityListener();
		}
		mActionBar.addOnMenuVisibilityListener(mMenuVisibilityListener);

		mHandler.sendEmptyMessageDelayed(MSG_UNFREEZE_GLROOT, UNFREEZE_GLROOT_TIMEOUT);
	}

	@Override
	protected void onStateResult(final int requestCode, final int resultCode, final Intent data) {
	}

	private boolean canShowBars() {
		// No bars if it's not allowed.
		if (!mActionBarAllowed) return false;

		return true;
	}

	private void hideBars() {
		if (!mShowBars) return;
		mShowBars = false;
		mActionBar.hide();
		mActivity.getGLRoot().setLightsOutMode(true);
		mHandler.removeMessages(MSG_HIDE_BARS);
	}

	private void hideDetails() {
	}

	private void refreshHidingMessage() {
		mHandler.removeMessages(MSG_HIDE_BARS);
		if (!mIsMenuVisible) {
			mHandler.sendEmptyMessageDelayed(MSG_HIDE_BARS, HIDE_BARS_TIMEOUT);
		}
	}

	private void setResult() {
		Intent result = null;
		if (!mPhotoView.getFilmMode()) {
			result = new Intent();
			result.putExtra(KEY_RETURN_INDEX_HINT, mCurrentIndex);
		}
		setStateResult(Activity.RESULT_OK, result);
	}

	private void showBars() {
		if (mShowBars) return;
		mShowBars = true;
		mOrientationManager.unlockOrientation();
		mActionBar.show();
		mActivity.getGLRoot().setLightsOutMode(false);
		refreshHidingMessage();
	}

	private void showDetails(final int index) {
	}

	private void toggleBars() {
		if (mShowBars) {
			hideBars();
		} else {
			if (canShowBars()) {
				showBars();
			}
		}
	}

	private void updateBars() {
		if (!canShowBars()) {
			hideBars();
		}
	}

	private void updateCurrentPhoto(final MediaItem photo) {
		if (mCurrentPhoto == photo) return;
		mCurrentPhoto = photo;
		if (mCurrentPhoto == null) return;
		updateMenuOperations();
		updateTitle();
	}

	private void updateMenuOperations() {
	}

	private void updateTitle() {
		if (mCurrentPhoto == null) return;
		final boolean showTitle = mActivity.getAndroidContext().getResources().getBoolean(R.bool.show_action_bar_title);
		if (showTitle && mCurrentPhoto.getName() != null) {
			mActionBar.setTitle(mCurrentPhoto.getName());
		} else {
			mActionBar.setTitle("");
		}
	}

	private void wantBars() {
		if (canShowBars()) {
			showBars();
		}
	}

	public static void playVideo(final Activity activity, final Uri uri, final String title) {
	}

	public static interface Model extends PhotoView.Model {
		public boolean isEmpty();

		public void pause();

		public void resume();

		public void setCurrentPhoto(Path path, int indexHint);
	}

	private class MyMenuVisibilityListener implements OnMenuVisibilityListener {
		@Override
		public void onMenuVisibilityChanged(final boolean isVisible) {
			mIsMenuVisible = isVisible;
			refreshHidingMessage();
		}
	}

	// private class PreparePhotoFallback implements OnGLIdleListener {
	// private PhotoFallbackEffect mPhotoFallback = new PhotoFallbackEffect();
	// private boolean mResultReady = false;
	//
	// public synchronized PhotoFallbackEffect get() {
	// while (!mResultReady) {
	// Utils.waitWithoutInterrupt(this);
	// }
	// return mPhotoFallback;
	// }
	//
	// @Override
	// public boolean onGLIdle(final GLCanvas canvas, final boolean
	// renderRequested) {
	// mPhotoFallback = mPhotoView.buildFallbackEffect(mRootPane, canvas);
	// synchronized (this) {
	// mResultReady = true;
	// notifyAll();
	// }
	// return false;
	// }
	// }
}
