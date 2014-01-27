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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.os.Handler;
import android.os.Message;

import com.gbnix.imageviewer.common.BitmapUtils;
import com.gbnix.imageviewer.common.Utils;
import com.gbnix.imageviewer.data.BitmapPool;
import com.gbnix.imageviewer.data.ContentListener;
import com.gbnix.imageviewer.data.DataManager;
import com.gbnix.imageviewer.data.MediaItem;
import com.gbnix.imageviewer.data.MediaObject;
import com.gbnix.imageviewer.data.MediaSet;
import com.gbnix.imageviewer.data.Path;
import com.gbnix.imageviewer.ui.BitmapScreenNail;
import com.gbnix.imageviewer.ui.PhotoView;
import com.gbnix.imageviewer.ui.ScreenNail;
import com.gbnix.imageviewer.ui.SynchronizedHandler;
import com.gbnix.imageviewer.ui.TileImageViewAdapter;
import com.gbnix.imageviewer.util.Future;
import com.gbnix.imageviewer.util.FutureListener;
import com.gbnix.imageviewer.util.ThreadPool;
import com.gbnix.imageviewer.util.ThreadPool.Job;
import com.gbnix.imageviewer.util.ThreadPool.JobContext;

public class PhotoDataAdapter implements PhotoPage.Model {
	@SuppressWarnings("unused")
	private static final String TAG = "PhotoDataAdapter";

	private static final int MSG_LOAD_START = 1;
	private static final int MSG_LOAD_FINISH = 2;
	private static final int MSG_RUN_OBJECT = 3;
	private static final int MSG_UPDATE_IMAGE_REQUESTS = 4;

	private static final int MIN_LOAD_COUNT = 8;
	private static final int DATA_CACHE_SIZE = 32;
	private static final int SCREEN_NAIL_MAX = PhotoView.SCREEN_NAIL_MAX;
	private static final int IMAGE_CACHE_SIZE = 2 * SCREEN_NAIL_MAX + 1;

	private static final int BIT_SCREEN_NAIL = 1;
	private static final int BIT_FULL_IMAGE = 2;

	// sImageFetchSeq is the fetching sequence for images.
	// We want to fetch the current screennail first (offset = 0), the next
	// screennail (offset = +1), then the previous screennail (offset = -1) etc.
	// After all the screennail are fetched, we fetch the full images (only some
	// of them because of we don't want to use too much memory).
	private static ImageFetch[] sImageFetchSeq;

	static {
		int k = 0;
		sImageFetchSeq = new ImageFetch[1 + (IMAGE_CACHE_SIZE - 1) * 2 + 3];
		sImageFetchSeq[k++] = new ImageFetch(0, BIT_SCREEN_NAIL);

		for (int i = 1; i < IMAGE_CACHE_SIZE; ++i) {
			sImageFetchSeq[k++] = new ImageFetch(i, BIT_SCREEN_NAIL);
			sImageFetchSeq[k++] = new ImageFetch(-i, BIT_SCREEN_NAIL);
		}

		sImageFetchSeq[k++] = new ImageFetch(0, BIT_FULL_IMAGE);
		sImageFetchSeq[k++] = new ImageFetch(1, BIT_FULL_IMAGE);
		sImageFetchSeq[k++] = new ImageFetch(-1, BIT_FULL_IMAGE);
	}

	private final TileImageViewAdapter mTileProvider = new TileImageViewAdapter();

	// PhotoDataAdapter caches MediaItems (data) and ImageEntries (image).
	//
	// The MediaItems are stored in the mData array, which has DATA_CACHE_SIZE
	// entries. The valid index range are [mContentStart, mContentEnd). We keep
	// mContentEnd - mContentStart <= DATA_CACHE_SIZE, so we can use
	// (i % DATA_CACHE_SIZE) as index to the array.
	//
	// The valid MediaItem window size (mContentEnd - mContentStart) may be
	// smaller than DATA_CACHE_SIZE because we only update the window and reload
	// the MediaItems when there are significant changes to the window position
	// (>= MIN_LOAD_COUNT).
	private final MediaItem mData[] = new MediaItem[DATA_CACHE_SIZE];

	private int mContentStart = 0;
	private int mContentEnd = 0;
	// The ImageCache is a Path-to-ImageEntry map. It only holds the
	// ImageEntries in the range of [mActiveStart, mActiveEnd). We also keep
	// mActiveEnd - mActiveStart <= IMAGE_CACHE_SIZE. Besides, the
	// [mActiveStart, mActiveEnd) range must be contained within
	// the [mContentStart, mContentEnd) range.
	private final HashMap<Path, ImageEntry> mImageCache = new HashMap<Path, ImageEntry>();

	private int mActiveStart = 0;
	private int mActiveEnd = 0;
	// mCurrentIndex is the "center" image the user is viewing. The change of
	// mCurrentIndex triggers the data loading and image loading.
	private int mCurrentIndex;

	// mChanges keeps the version number (of MediaItem) about the images. If any
	// of the version number changes, we notify the view. This is used after a
	// database reload or mCurrentIndex changes.
	private final long mChanges[] = new long[IMAGE_CACHE_SIZE];

	// mPaths keeps the corresponding Path (of MediaItem) for the images. This
	// is used to determine the item movement.
	private final Path mPaths[] = new Path[IMAGE_CACHE_SIZE];
	private final Handler mMainHandler;

	private final ThreadPool mThreadPool;
	private final PhotoView mPhotoView;

	private final MediaSet mSource;
	private ReloadTask mReloadTask;
	private long mSourceVersion = MediaObject.INVALID_DATA_VERSION;

	private int mSize = 0;
	private Path mItemPath;
	private boolean mIsActive;
	private boolean mNeedFullImage;
	private int mFocusHintDirection = FOCUS_HINT_NEXT;
	private Path mFocusHintPath = null;
	private DataListener mDataListener;

	private final SourceListener mSourceListener = new SourceListener();

	// The path of the current viewing item will be stored in mItemPath.
	// If mItemPath is not null, mCurrentIndex is only a hint for where we
	// can find the item. If mItemPath is null, then we use the mCurrentIndex to
	// find the image being viewed. cameraIndex is the index of the camera
	// preview. If cameraIndex < 0, there is no camera preview.
	public PhotoDataAdapter(final GalleryActivity activity, final PhotoView view, final MediaSet mediaSet,
			final Path itemPath, final int indexHint) {
		mSource = Utils.checkNotNull(mediaSet);
		mPhotoView = Utils.checkNotNull(view);
		mItemPath = Utils.checkNotNull(itemPath);
		mCurrentIndex = indexHint;
		mThreadPool = activity.getThreadPool();
		mNeedFullImage = true;

		Arrays.fill(mChanges, MediaObject.INVALID_DATA_VERSION);

		mMainHandler = new SynchronizedHandler(activity.getGLRoot()) {
			@Override
			public void handleMessage(final Message message) {
				switch (message.what) {
					case MSG_RUN_OBJECT:
						((Runnable) message.obj).run();
						return;
					case MSG_LOAD_START: {
						if (mDataListener != null) {
							mDataListener.onLoadingStarted();
						}
						return;
					}
					case MSG_LOAD_FINISH: {
						if (mDataListener != null) {
							mDataListener.onLoadingFinished();
						}
						return;
					}
					case MSG_UPDATE_IMAGE_REQUESTS: {
						updateImageRequests();
						return;
					}
					default:
						throw new AssertionError();
				}
			}
		};

		updateSlidingWindow();
	}

	@Override
	public int getCurrentIndex() {
		return mCurrentIndex;
	}

	@Override
	public int getImageHeight() {
		return mTileProvider.getImageHeight();
	}

	@Override
	public int getImageRotation(final int offset) {
		final MediaItem item = getItem(mCurrentIndex + offset);
		return item == null ? 0 : item.getFullImageRotation();
	}

	@Override
	public void getImageSize(final int offset, final PhotoView.Size size) {
		final MediaItem item = getItem(mCurrentIndex + offset);
		if (item == null) {
			size.width = 0;
			size.height = 0;
		} else {
			size.width = item.getWidth();
			size.height = item.getHeight();
		}
	}

	@Override
	public int getImageWidth() {
		return mTileProvider.getImageWidth();
	}

	@Override
	public int getLevelCount() {
		return mTileProvider.getLevelCount();
	}

	@Override
	public int getLoadingState(final int offset) {
		final ImageEntry entry = mImageCache.get(getPath(mCurrentIndex + offset));
		if (entry == null) return LOADING_INIT;
		if (entry.failToLoad) return LOADING_FAIL;
		if (entry.screenNail != null) return LOADING_COMPLETE;
		return LOADING_INIT;
	}

	@Override
	public MediaItem getMediaItem(final int offset) {
		final int index = mCurrentIndex + offset;
		if (index >= mContentStart && index < mContentEnd) return mData[index % DATA_CACHE_SIZE];
		return null;
	}

	@Override
	public ScreenNail getScreenNail() {
		return getScreenNail(0);
	}

	@Override
	public ScreenNail getScreenNail(final int offset) {
		final int index = mCurrentIndex + offset;
		if (index < 0 || index >= mSize || !mIsActive) return null;
		Utils.assertTrue(index >= mActiveStart && index < mActiveEnd);

		final MediaItem item = getItem(index);
		if (item == null) return null;

		final ImageEntry entry = mImageCache.get(item.getPath());
		if (entry == null) return null;

		// Create a default ScreenNail if the real one is not available yet,
		// except for camera that a black screen is better than a gray tile.
		if (entry.screenNail == null && !isCamera(offset)) {
			entry.screenNail = newPlaceholderScreenNail(item);
			if (offset == 0) {
				updateTileProvider(entry);
			}
		}

		return entry.screenNail;
	}

	@Override
	public Bitmap getTile(final int level, final int x, final int y, final int tileSize, final int borderSize,
			final BitmapPool pool) {
		return mTileProvider.getTile(level, x, y, tileSize, borderSize, pool);
	}

	@Override
	public boolean isCamera(final int offset) {
		return false;
	}

	@Override
	public boolean isEmpty() {
		return mSize == 0;
	}

	@Override
	public boolean isLoadComplete(final int offset) {
		final ImageEntry entry = mImageCache.get(getPath(mCurrentIndex + offset));
		return entry != null && (entry.fullImage != null || entry.screenNail != null && entry.screenNail.isReady());
	}

	@Override
	public boolean isLoadFailed(final int offset) {
		final ImageEntry entry = mImageCache.get(getPath(mCurrentIndex + offset));
		return entry != null && entry.failToLoad;
	}

	@Override
	public void moveTo(final int index) {
		updateCurrentIndex(index);
	}

	@Override
	public void pause() {
		mIsActive = false;

		mReloadTask.terminate();
		mReloadTask = null;

		mSource.removeContentListener(mSourceListener);

		for (final ImageEntry entry : mImageCache.values()) {
			if (entry.fullImageTask != null) {
				entry.fullImageTask.cancel();
			}
			if (entry.screenNailTask != null) {
				entry.screenNailTask.cancel();
			}
			if (entry.screenNail != null) {
				entry.screenNail.recycle();
			}
		}
		mImageCache.clear();
		mTileProvider.clear();
	}

	@Override
	public void resume() {
		mIsActive = true;
		mSource.addContentListener(mSourceListener);
		updateImageCache();
		updateImageRequests();

		mReloadTask = new ReloadTask();
		mReloadTask.start();

		fireDataChange();
	}

	@Override
	public void setCurrentPhoto(final Path path, final int indexHint) {
		if (mItemPath == path) return;
		mItemPath = path;
		mCurrentIndex = indexHint;
		updateSlidingWindow();
		updateImageCache();
		fireDataChange();

		// We need to reload content if the path doesn't match.
		final MediaItem item = getMediaItem(0);
		if (item != null && item.getPath() != path) {
			if (mReloadTask != null) {
				mReloadTask.notifyDirty();
			}
		}
	}

	public void setDataListener(final DataListener listener) {
		mDataListener = listener;
	}

	@Override
	public void setFocusHintDirection(final int direction) {
		mFocusHintDirection = direction;
	}

	@Override
	public void setFocusHintPath(final Path path) {
		mFocusHintPath = path;
	}

	@Override
	public void setNeedFullImage(final boolean enabled) {
		mNeedFullImage = enabled;
		mMainHandler.sendEmptyMessage(MSG_UPDATE_IMAGE_REQUESTS);
	}

	private <T> T executeAndWait(final Callable<T> callable) {
		final FutureTask<T> task = new FutureTask<T>(callable);
		mMainHandler.sendMessage(mMainHandler.obtainMessage(MSG_RUN_OBJECT, task));
		try {
			return task.get();
		} catch (final InterruptedException e) {
			return null;
		} catch (final ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	private void fireDataChange() {
		// First check if data actually changed.
		boolean changed = false;
		for (int i = -SCREEN_NAIL_MAX; i <= SCREEN_NAIL_MAX; ++i) {
			final long newVersion = getVersion(mCurrentIndex + i);
			if (mChanges[i + SCREEN_NAIL_MAX] != newVersion) {
				mChanges[i + SCREEN_NAIL_MAX] = newVersion;
				changed = true;
			}
		}

		if (!changed) return;

		// Now calculate the fromIndex array. fromIndex represents the item
		// movement. It records the index where the picture come from. The
		// special value Integer.MAX_VALUE means it's a new picture.
		final int N = IMAGE_CACHE_SIZE;
		final int fromIndex[] = new int[N];

		// Remember the old path array.
		final Path oldPaths[] = new Path[N];
		System.arraycopy(mPaths, 0, oldPaths, 0, N);

		// Update the mPaths array.
		for (int i = 0; i < N; ++i) {
			mPaths[i] = getPath(mCurrentIndex + i - SCREEN_NAIL_MAX);
		}

		// Calculate the fromIndex array.
		for (int i = 0; i < N; i++) {
			final Path p = mPaths[i];
			if (p == null) {
				fromIndex[i] = Integer.MAX_VALUE;
				continue;
			}

			// Try to find the same path in the old array
			int j;
			for (j = 0; j < N; j++) {
				if (oldPaths[j] == p) {
					break;
				}
			}
			fromIndex[i] = j < N ? j - SCREEN_NAIL_MAX : Integer.MAX_VALUE;
		}

		mPhotoView.notifyDataChange(fromIndex, -mCurrentIndex, mSize - 1 - mCurrentIndex);
	}

	private MediaItem getItem(final int index) {
		if (index < 0 || index >= mSize || !mIsActive) return null;
		Utils.assertTrue(index >= mActiveStart && index < mActiveEnd);

		if (index >= mContentStart && index < mContentEnd) return mData[index % DATA_CACHE_SIZE];
		return null;
	}

	private MediaItem getItemInternal(final int index) {
		if (index < 0 || index >= mSize) return null;
		if (index >= mContentStart && index < mContentEnd) return mData[index % DATA_CACHE_SIZE];
		return null;
	}

	private Path getPath(final int index) {
		final MediaItem item = getItemInternal(index);
		if (item == null) return null;
		return item.getPath();
	}

	private long getVersion(final int index) {
		final MediaItem item = getItemInternal(index);
		if (item == null) return MediaObject.INVALID_DATA_VERSION;
		return item.getDataVersion();
	}

	// Returns true if we think this is a temporary item created by Camera. A
	// temporary item is an image or a video whose data is still being
	// processed, but an incomplete entry is created first in MediaProvider, so
	// we can display them (in grey tile) even if they are not saved to disk
	// yet. When the image or video data is actually saved, we will get
	// notification from MediaProvider, reload data, and show the actual image
	// or video data.
	private boolean isTemporaryItem(final MediaItem mediaItem) {
		// Must have camera to create a temporary item.
		return false;
	}

	// Create a default ScreenNail when a ScreenNail is needed, but we don't yet
	// have one available (because the image data is still being saved, or the
	// Bitmap is still being loaded.
	private ScreenNail newPlaceholderScreenNail(final MediaItem item) {
		final int width = item.getWidth();
		final int height = item.getHeight();
		return new BitmapScreenNail(width, height);
	}

	// Returns the task if we started the task or the task is already started.
	private Future<?> startTaskIfNeeded(final int index, final int which) {
		if (index < mActiveStart || index >= mActiveEnd) return null;

		final ImageEntry entry = mImageCache.get(getPath(index));
		if (entry == null) return null;
		final MediaItem item = mData[index % DATA_CACHE_SIZE];
		Utils.assertTrue(item != null);
		final long version = item.getDataVersion();

		if (which == BIT_SCREEN_NAIL && entry.screenNailTask != null && entry.requestedScreenNail == version)
			return entry.screenNailTask;
		else if (which == BIT_FULL_IMAGE && entry.fullImageTask != null && entry.requestedFullImage == version)
			return entry.fullImageTask;

		if (which == BIT_SCREEN_NAIL && entry.requestedScreenNail != version) {
			entry.requestedScreenNail = version;
			entry.screenNailTask = mThreadPool.submit(new ScreenNailJob(item), new ScreenNailListener(item));
			// request screen nail
			return entry.screenNailTask;
		}
		if (which == BIT_FULL_IMAGE && entry.requestedFullImage != version
				&& (item.getSupportedOperations() & MediaItem.SUPPORT_FULL_IMAGE) != 0) {
			entry.requestedFullImage = version;
			entry.fullImageTask = mThreadPool.submit(new FullImageJob(item), new FullImageListener(item));
			// request full image
			return entry.fullImageTask;
		}
		return null;
	}

	private void updateCurrentIndex(final int index) {
		if (mCurrentIndex == index) return;
		mCurrentIndex = index;
		updateSlidingWindow();

		final MediaItem item = mData[index % DATA_CACHE_SIZE];
		mItemPath = item == null ? null : item.getPath();

		updateImageCache();
		updateImageRequests();
		updateTileProvider();

		if (mDataListener != null) {
			mDataListener.onPhotoChanged(index, mItemPath);
		}

		fireDataChange();
	}

	private void updateFullImage(final Path path, final Future<BitmapRegionDecoder> future) {
		final ImageEntry entry = mImageCache.get(path);
		if (entry == null || entry.fullImageTask != future) {
			final BitmapRegionDecoder fullImage = future.get();
			if (fullImage != null) {
				fullImage.recycle();
			}
			return;
		}

		entry.fullImageTask = null;
		entry.fullImage = future.get();
		if (entry.fullImage != null) {
			if (path == getPath(mCurrentIndex)) {
				updateTileProvider(entry);
				mPhotoView.notifyImageChange(0);
			}
		}
		updateImageRequests();
	}

	private void updateImageCache() {
		final HashSet<Path> toBeRemoved = new HashSet<Path>(mImageCache.keySet());
		for (int i = mActiveStart; i < mActiveEnd; ++i) {
			final MediaItem item = mData[i % DATA_CACHE_SIZE];
			if (item == null) {
				continue;
			}
			final Path path = item.getPath();
			ImageEntry entry = mImageCache.get(path);
			toBeRemoved.remove(path);
			if (entry != null) {
				if (Math.abs(i - mCurrentIndex) > 1) {
					if (entry.fullImageTask != null) {
						entry.fullImageTask.cancel();
						entry.fullImageTask = null;
					}
					entry.fullImage = null;
					entry.requestedFullImage = MediaObject.INVALID_DATA_VERSION;
				}
				if (entry.requestedScreenNail != item.getDataVersion()) {
					// This ScreenNail is outdated, we want to update it if it's
					// still a placeholder.
					if (entry.screenNail instanceof BitmapScreenNail) {
						final BitmapScreenNail s = (BitmapScreenNail) entry.screenNail;
						s.updatePlaceholderSize(item.getWidth(), item.getHeight());
					}
				}
			} else {
				entry = new ImageEntry();
				mImageCache.put(path, entry);
			}
		}

		// Clear the data and requests for ImageEntries outside the new window.
		for (final Path path : toBeRemoved) {
			final ImageEntry entry = mImageCache.remove(path);
			if (entry.fullImageTask != null) {
				entry.fullImageTask.cancel();
			}
			if (entry.screenNailTask != null) {
				entry.screenNailTask.cancel();
			}
			if (entry.screenNail != null) {
				entry.screenNail.recycle();
			}
		}
	}

	private void updateImageRequests() {
		if (!mIsActive) return;

		final int currentIndex = mCurrentIndex;
		final MediaItem item = mData[currentIndex % DATA_CACHE_SIZE];
		// current item mismatch - don't request image
		if (item == null || item.getPath() != mItemPath) return;

		// 1. Find the most wanted request and start it (if not already
		// started).
		Future<?> task = null;
		for (final ImageFetch element : sImageFetchSeq) {
			final int offset = element.indexOffset;
			final int bit = element.imageBit;
			if (bit == BIT_FULL_IMAGE && !mNeedFullImage) {
				continue;
			}
			task = startTaskIfNeeded(currentIndex + offset, bit);
			if (task != null) {
				break;
			}
		}

		// 2. Cancel everything else.
		for (final ImageEntry entry : mImageCache.values()) {
			if (entry.screenNailTask != null && entry.screenNailTask != task) {
				entry.screenNailTask.cancel();
				entry.screenNailTask = null;
				entry.requestedScreenNail = MediaObject.INVALID_DATA_VERSION;
			}
			if (entry.fullImageTask != null && entry.fullImageTask != task) {
				entry.fullImageTask.cancel();
				entry.fullImageTask = null;
				entry.requestedFullImage = MediaObject.INVALID_DATA_VERSION;
			}
		}
	}

	private void updateScreenNail(final Path path, final Future<ScreenNail> future) {
		final ImageEntry entry = mImageCache.get(path);
		ScreenNail screenNail = future.get();

		if (entry == null || entry.screenNailTask != future) {
			if (screenNail != null) {
				screenNail.recycle();
			}
			return;
		}

		entry.screenNailTask = null;

		// Combine the ScreenNails if we already have a BitmapScreenNail
		if (entry.screenNail instanceof BitmapScreenNail) {
			final BitmapScreenNail original = (BitmapScreenNail) entry.screenNail;
			screenNail = original.combine(screenNail);
		}

		if (screenNail == null) {
			entry.failToLoad = true;
		} else {
			entry.failToLoad = false;
			entry.screenNail = screenNail;
		}

		for (int i = -SCREEN_NAIL_MAX; i <= SCREEN_NAIL_MAX; ++i) {
			if (path == getPath(mCurrentIndex + i)) {
				if (i == 0) {
					updateTileProvider(entry);
				}
				mPhotoView.notifyImageChange(i);
				break;
			}
		}
		updateImageRequests();
	}

	private void updateSlidingWindow() {
		// 1. Update the image window
		int start = Utils.clamp(mCurrentIndex - IMAGE_CACHE_SIZE / 2, 0, Math.max(0, mSize - IMAGE_CACHE_SIZE));
		int end = Math.min(mSize, start + IMAGE_CACHE_SIZE);

		if (mActiveStart == start && mActiveEnd == end) return;

		mActiveStart = start;
		mActiveEnd = end;

		// 2. Update the data window
		start = Utils.clamp(mCurrentIndex - DATA_CACHE_SIZE / 2, 0, Math.max(0, mSize - DATA_CACHE_SIZE));
		end = Math.min(mSize, start + DATA_CACHE_SIZE);
		if (mContentStart > mActiveStart || mContentEnd < mActiveEnd
				|| Math.abs(start - mContentStart) > MIN_LOAD_COUNT) {
			for (int i = mContentStart; i < mContentEnd; ++i) {
				if (i < start || i >= end) {
					mData[i % DATA_CACHE_SIZE] = null;
				}
			}
			mContentStart = start;
			mContentEnd = end;
			if (mReloadTask != null) {
				mReloadTask.notifyDirty();
			}
		}
	}

	private void updateTileProvider() {
		final ImageEntry entry = mImageCache.get(getPath(mCurrentIndex));
		if (entry == null) { // in loading
			mTileProvider.clear();
		} else {
			updateTileProvider(entry);
		}
	}

	private void updateTileProvider(final ImageEntry entry) {
		final ScreenNail screenNail = entry.screenNail;
		final BitmapRegionDecoder fullImage = entry.fullImage;
		if (screenNail != null) {
			if (fullImage != null) {
				mTileProvider.setScreenNail(screenNail, fullImage.getWidth(), fullImage.getHeight());
				mTileProvider.setRegionDecoder(fullImage);
			} else {
				final int width = screenNail.getWidth();
				final int height = screenNail.getHeight();
				mTileProvider.setScreenNail(screenNail, width, height);
			}
		} else {
			mTileProvider.clear();
		}
	}

	public interface DataListener extends LoadingListener {
		public void onPhotoChanged(int index, Path item);
	}

	private class FullImageJob implements Job<BitmapRegionDecoder> {
		private final MediaItem mItem;

		public FullImageJob(final MediaItem item) {
			mItem = item;
		}

		@Override
		public BitmapRegionDecoder run(final JobContext jc) {
			if (isTemporaryItem(mItem)) return null;
			return mItem.requestLargeImage().run(jc);
		}
	}

	private class FullImageListener implements Runnable, FutureListener<BitmapRegionDecoder> {
		private final Path mPath;
		private Future<BitmapRegionDecoder> mFuture;

		public FullImageListener(final MediaItem item) {
			mPath = item.getPath();
		}

		@Override
		public void onFutureDone(final Future<BitmapRegionDecoder> future) {
			mFuture = future;
			mMainHandler.sendMessage(mMainHandler.obtainMessage(MSG_RUN_OBJECT, this));
		}

		@Override
		public void run() {
			updateFullImage(mPath, mFuture);
		}
	}

	private class GetUpdateInfo implements Callable<UpdateInfo> {

		@Override
		public UpdateInfo call() throws Exception {
			// TODO: Try to load some data in first update
			final UpdateInfo info = new UpdateInfo();
			info.version = mSourceVersion;
			info.reloadContent = needContentReload();
			info.target = mItemPath;
			info.indexHint = mCurrentIndex;
			info.contentStart = mContentStart;
			info.contentEnd = mContentEnd;
			info.size = mSize;
			return info;
		}

		private boolean needContentReload() {
			for (int i = mContentStart, n = mContentEnd; i < n; ++i) {
				if (mData[i % DATA_CACHE_SIZE] == null) return true;
			}
			final MediaItem current = mData[mCurrentIndex % DATA_CACHE_SIZE];
			return current == null || current.getPath() != mItemPath;
		}
	}

	private static class ImageEntry {
		public BitmapRegionDecoder fullImage;
		public ScreenNail screenNail;
		public Future<ScreenNail> screenNailTask;
		public Future<BitmapRegionDecoder> fullImageTask;
		public long requestedScreenNail = MediaObject.INVALID_DATA_VERSION;
		public long requestedFullImage = MediaObject.INVALID_DATA_VERSION;
		public boolean failToLoad = false;
	}

	private static class ImageFetch {
		int indexOffset;
		int imageBit;

		public ImageFetch(final int offset, final int bit) {
			indexOffset = offset;
			imageBit = bit;
		}
	}

	private class ReloadTask extends Thread {
		private volatile boolean mActive = true;
		private volatile boolean mDirty = true;

		private boolean mIsLoading = false;

		public synchronized void notifyDirty() {
			mDirty = true;
			notifyAll();
		}

		@Override
		public void run() {
			while (mActive) {
				synchronized (this) {
					if (!mDirty && mActive) {
						updateLoading(false);
						Utils.waitWithoutInterrupt(this);
						continue;
					}
				}
				mDirty = false;
				final UpdateInfo info = executeAndWait(new GetUpdateInfo());
				synchronized (DataManager.LOCK) {
					updateLoading(true);
					final long version = mSource.reload();
					if (info.version != version) {
						info.reloadContent = true;
						info.size = mSource.getMediaItemCount();
					}
					if (!info.reloadContent) {
						continue;
					}
					info.items = mSource.getMediaItem(info.contentStart, info.contentEnd);

					int index = MediaSet.INDEX_NOT_FOUND;

					// First try to focus on the given hint path if there is
					// one.
					if (mFocusHintPath != null) {
						index = findIndexOfPathInCache(info, mFocusHintPath);
						mFocusHintPath = null;
					}

					// Otherwise try to see if the currently focused item can be
					// found.
					if (index == MediaSet.INDEX_NOT_FOUND) {
						final MediaItem item = findCurrentMediaItem(info);
						if (item != null && item.getPath() == info.target) {
							index = info.indexHint;
						} else {
							index = findIndexOfTarget(info);
						}
					}

					// The image has been deleted. Focus on the next image (keep
					// mCurrentIndex unchanged) or the previous image (decrease
					// mCurrentIndex by 1). In page mode we want to see the next
					// image, so we focus on the next one. In film mode we want
					// the
					// later images to shift left to fill the empty space, so we
					// focus on the previous image (so it will not move). In any
					// case the index needs to be limited to [0, mSize).
					if (index == MediaSet.INDEX_NOT_FOUND) {
						index = info.indexHint;
						if (mFocusHintDirection == FOCUS_HINT_PREVIOUS && index > 0) {
							index--;
						}
					}

					// Don't change index if mSize == 0
					if (mSize > 0) {
						if (index >= mSize) {
							index = mSize - 1;
						}
						info.indexHint = index;
					}
				}

				executeAndWait(new UpdateContent(info));
			}
		}

		public synchronized void terminate() {
			mActive = false;
			notifyAll();
		}

		private MediaItem findCurrentMediaItem(final UpdateInfo info) {
			final ArrayList<MediaItem> items = info.items;
			final int index = info.indexHint - info.contentStart;
			return index < 0 || index >= items.size() ? null : items.get(index);
		}

		private int findIndexOfPathInCache(final UpdateInfo info, final Path path) {
			final ArrayList<MediaItem> items = info.items;
			for (int i = 0, n = items.size(); i < n; ++i) {
				if (items.get(i).getPath() == path) return i + info.contentStart;
			}
			return MediaSet.INDEX_NOT_FOUND;
		}

		private int findIndexOfTarget(final UpdateInfo info) {
			if (info.target == null) return info.indexHint;
			final ArrayList<MediaItem> items = info.items;

			// First, try to find the item in the data just loaded
			if (items != null) {
				final int i = findIndexOfPathInCache(info, info.target);
				if (i != MediaSet.INDEX_NOT_FOUND) return i;
			}

			// Not found, find it in mSource.
			return mSource.getIndexOfItem(info.target, info.indexHint);
		}

		private void updateLoading(final boolean loading) {
			if (mIsLoading == loading) return;
			mIsLoading = loading;
			mMainHandler.sendEmptyMessage(loading ? MSG_LOAD_START : MSG_LOAD_FINISH);
		}
	}

	private class ScreenNailJob implements Job<ScreenNail> {
		private final MediaItem mItem;

		public ScreenNailJob(final MediaItem item) {
			mItem = item;
		}

		@Override
		public ScreenNail run(final JobContext jc) {
			// We try to get a ScreenNail first, if it fails, we fallback to get
			// a Bitmap and then wrap it in a BitmapScreenNail instead.
			final ScreenNail s = mItem.getScreenNail();
			if (s != null) return s;

			// If this is a temporary item, don't try to get its bitmap because
			// it won't be available. We will get its bitmap after a data
			// reload.
			if (isTemporaryItem(mItem)) return newPlaceholderScreenNail(mItem);

			Bitmap bitmap = mItem.requestImage(MediaItem.TYPE_THUMBNAIL).run(jc);
			if (jc.isCancelled()) return null;
			if (bitmap != null) {
				bitmap = BitmapUtils.rotateBitmap(bitmap, mItem.getRotation() - mItem.getFullImageRotation(), true);
			}
			return bitmap == null ? null : new BitmapScreenNail(bitmap);
		}
	}

	private class ScreenNailListener implements Runnable, FutureListener<ScreenNail> {
		private final Path mPath;
		private Future<ScreenNail> mFuture;

		public ScreenNailListener(final MediaItem item) {
			mPath = item.getPath();
		}

		@Override
		public void onFutureDone(final Future<ScreenNail> future) {
			mFuture = future;
			mMainHandler.sendMessage(mMainHandler.obtainMessage(MSG_RUN_OBJECT, this));
		}

		@Override
		public void run() {
			updateScreenNail(mPath, mFuture);
		}
	}

	private class SourceListener implements ContentListener {
		@Override
		public void onContentDirty() {
			if (mReloadTask != null) {
				mReloadTask.notifyDirty();
			}
		}
	}

	private class UpdateContent implements Callable<Void> {
		UpdateInfo mUpdateInfo;

		public UpdateContent(final UpdateInfo updateInfo) {
			mUpdateInfo = updateInfo;
		}

		@Override
		public Void call() throws Exception {
			final UpdateInfo info = mUpdateInfo;
			mSourceVersion = info.version;

			if (info.size != mSize) {
				mSize = info.size;
				if (mContentEnd > mSize) {
					mContentEnd = mSize;
				}
				if (mActiveEnd > mSize) {
					mActiveEnd = mSize;
				}
			}

			mCurrentIndex = info.indexHint;
			updateSlidingWindow();

			if (info.items != null) {
				final int start = Math.max(info.contentStart, mContentStart);
				final int end = Math.min(info.contentStart + info.items.size(), mContentEnd);
				int dataIndex = start % DATA_CACHE_SIZE;
				for (int i = start; i < end; ++i) {
					mData[dataIndex] = info.items.get(i - info.contentStart);
					if (++dataIndex == DATA_CACHE_SIZE) {
						dataIndex = 0;
					}
				}
			}

			// update mItemPath
			final MediaItem current = mData[mCurrentIndex % DATA_CACHE_SIZE];
			mItemPath = current == null ? null : current.getPath();

			updateImageCache();
			updateTileProvider();
			updateImageRequests();
			fireDataChange();
			return null;
		}
	}

	private static class UpdateInfo {
		public long version;
		public boolean reloadContent;
		public Path target;
		public int indexHint;
		public int contentStart;
		public int contentEnd;

		public int size;
		public ArrayList<MediaItem> items;
	}
}
