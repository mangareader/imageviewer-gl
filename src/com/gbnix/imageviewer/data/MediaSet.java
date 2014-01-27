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

package com.gbnix.imageviewer.data;

import java.util.ArrayList;
import java.util.WeakHashMap;

import com.gbnix.imageviewer.common.Utils;
import com.gbnix.imageviewer.util.Future;

// MediaSet is a directory-like data structure.
// It contains MediaItems and sub-MediaSets.
//
// The primary interface are:
// getMediaItemCount(), getMediaItem() and
// getSubMediaSetCount(), getSubMediaSet().
//
// getTotalMediaItemCount() returns the number of all MediaItems, including
// those in sub-MediaSets.
public abstract class MediaSet extends MediaObject {
	private static final String TAG = "MediaSet";

	public static final int MEDIAITEM_BATCH_FETCH_COUNT = 500;
	public static final int INDEX_NOT_FOUND = -1;

	public static final int SYNC_RESULT_SUCCESS = 0;
	public static final int SYNC_RESULT_CANCELLED = 1;
	public static final int SYNC_RESULT_ERROR = 2;

	private final WeakHashMap<ContentListener, Object> mListeners = new WeakHashMap<ContentListener, Object>();

	private static final Future<Integer> FUTURE_STUB = new Future<Integer>() {
		@Override
		public void cancel() {
		}

		@Override
		public Integer get() {
			return SYNC_RESULT_SUCCESS;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean isDone() {
			return true;
		}

		@Override
		public void waitDone() {
		}
	};

	public MediaSet(final Path path, final long version) {
		super(path, version);
	}

	// NOTE: The MediaSet only keeps a weak reference to the listener. The
	// listener is automatically removed when there is no other reference to
	// the listener.
	public void addContentListener(final ContentListener listener) {
		if (mListeners.containsKey(listener)) throw new IllegalArgumentException();
		mListeners.put(listener, null);
	}

	// Enumerate all media items in this media set (including the ones in sub
	// media sets), in an efficient order. ItemConsumer.consumer() will be
	// called for each media item with its index.
	public void enumerateMediaItems(final ItemConsumer consumer) {
		enumerateMediaItems(consumer, 0);
	}

	public void enumerateTotalMediaItems(final ItemConsumer consumer) {
		enumerateTotalMediaItems(consumer, 0);
	}

	public MediaItem getCoverMediaItem() {
		final ArrayList<MediaItem> items = getMediaItem(0, 1);
		if (items.size() > 0) return items.get(0);
		for (int i = 0, n = getSubMediaSetCount(); i < n; i++) {
			final MediaItem cover = getSubMediaSet(i).getCoverMediaItem();
			if (cover != null) return cover;
		}
		return null;
	}

	@Override
	public MediaDetails getDetails() {
		final MediaDetails details = super.getDetails();
		details.addDetail(MediaDetails.INDEX_TITLE, getName());
		return details;
	}

	// TODO: we should have better implementation of sub classes
	public int getIndexOfItem(final Path path, final int hint) {
		// hint < 0 is handled below
		// first, try to find it around the hint
		int start = Math.max(0, hint - MEDIAITEM_BATCH_FETCH_COUNT / 2);
		ArrayList<MediaItem> list = getMediaItem(start, MEDIAITEM_BATCH_FETCH_COUNT);
		int index = getIndexOf(path, list);
		if (index != INDEX_NOT_FOUND) return start + index;

		// try to find it globally
		start = start == 0 ? MEDIAITEM_BATCH_FETCH_COUNT : 0;
		list = getMediaItem(start, MEDIAITEM_BATCH_FETCH_COUNT);
		while (true) {
			index = getIndexOf(path, list);
			if (index != INDEX_NOT_FOUND) return start + index;
			if (list.size() < MEDIAITEM_BATCH_FETCH_COUNT) return INDEX_NOT_FOUND;
			start += MEDIAITEM_BATCH_FETCH_COUNT;
			list = getMediaItem(start, MEDIAITEM_BATCH_FETCH_COUNT);
		}
	}

	// Returns the media items in the range [start, start + count).
	//
	// The number of media items returned may be less than the specified count
	// if there are not enough media items available. The number of
	// media items available may not be consistent with the return value of
	// getMediaItemCount() because the contents of database may have already
	// changed.
	public ArrayList<MediaItem> getMediaItem(final int start, final int count) {
		return new ArrayList<MediaItem>();
	}

	public int getMediaItemCount() {
		return 0;
	}

	public abstract String getName();

	public MediaSet getSubMediaSet(final int index) {
		throw new IndexOutOfBoundsException();
	}

	public int getSubMediaSetCount() {
		return 0;
	}

	public int getTotalMediaItemCount() {
		int total = getMediaItemCount();
		for (int i = 0, n = getSubMediaSetCount(); i < n; i++) {
			total += getSubMediaSet(i).getTotalMediaItemCount();
		}
		return total;
	}

	public boolean isLeafAlbum() {
		return false;
	}

	/**
	 * Method {@link #reload()} may process the loading task in background, this
	 * method tells its client whether the loading is still in process or not.
	 */
	public boolean isLoading() {
		return false;
	}

	// This should be called by subclasses when the content is changed.
	public void notifyContentChanged() {
		for (final ContentListener listener : mListeners.keySet()) {
			listener.onContentDirty();
		}
	}

	// Reload the content. Return the current data version. reload() should be
	// called
	// in the same thread as getMediaItem(int, int) and getSubMediaSet(int).
	public abstract long reload();

	public void removeContentListener(final ContentListener listener) {
		if (!mListeners.containsKey(listener)) throw new IllegalArgumentException();
		mListeners.remove(listener);
	}

	/**
	 * Requests sync on this MediaSet. It returns a Future object that can be
	 * used by the caller to query the status of the sync. The sync result code
	 * is one of the SYNC_RESULT_* constants defined in this class and can be
	 * obtained by Future.get().
	 * 
	 * Subclasses should perform sync on a different thread.
	 * 
	 * The default implementation here returns a Future stub that does nothing
	 * and returns SYNC_RESULT_SUCCESS by get().
	 */
	public Future<Integer> requestSync(final SyncListener listener) {
		listener.onSyncDone(this, SYNC_RESULT_SUCCESS);
		return FUTURE_STUB;
	}

	// The default implementation uses getMediaItem() for enumerateMediaItems().
	// Subclasses may override this and use more efficient implementations.
	// Returns the number of items enumerated.
	protected int enumerateMediaItems(final ItemConsumer consumer, final int startIndex) {
		final int total = getMediaItemCount();
		int start = 0;
		while (start < total) {
			final int count = Math.min(MEDIAITEM_BATCH_FETCH_COUNT, total - start);
			final ArrayList<MediaItem> items = getMediaItem(start, count);
			for (int i = 0, n = items.size(); i < n; i++) {
				final MediaItem item = items.get(i);
				consumer.consume(startIndex + start + i, item);
			}
			start += count;
		}
		return total;
	}

	// Recursively enumerate all media items under this set.
	// Returns the number of items enumerated.
	protected int enumerateTotalMediaItems(final ItemConsumer consumer, final int startIndex) {
		int start = 0;
		start += enumerateMediaItems(consumer, startIndex);
		final int m = getSubMediaSetCount();
		for (int i = 0; i < m; i++) {
			start += getSubMediaSet(i).enumerateTotalMediaItems(consumer, startIndex + start);
		}
		return start;
	}

	protected int getIndexOf(final Path path, final ArrayList<MediaItem> list) {
		for (int i = 0, n = list.size(); i < n; ++i) {
			if (list.get(i).mPath == path) return i;
		}
		return INDEX_NOT_FOUND;
	}

	protected Future<Integer> requestSyncOnMultipleSets(final MediaSet[] sets, final SyncListener listener) {
		return new MultiSetSyncFuture(sets, listener);
	}

	public static interface ItemConsumer {
		void consume(int index, MediaItem item);
	}

	/** Listener to be used with requestSync(SyncListener). */
	public static interface SyncListener {
		/**
		 * Called when the sync task completed. Completion may be due to normal
		 * termination, an exception, or cancellation.
		 * 
		 * @param mediaSet the MediaSet that's done with sync
		 * @param resultCode one of the SYNC_RESULT_* constants
		 */
		void onSyncDone(MediaSet mediaSet, int resultCode);
	}

	private class MultiSetSyncFuture implements Future<Integer>, SyncListener {
		private static final String TAG = "Gallery.MultiSetSync";

		private final SyncListener mListener;
		private final Future<Integer> mFutures[];

		private boolean mIsCancelled = false;
		private int mResult = -1;
		private int mPendingCount;

		@SuppressWarnings("unchecked")
		MultiSetSyncFuture(final MediaSet[] sets, final SyncListener listener) {
			mListener = listener;
			mPendingCount = sets.length;
			mFutures = new Future[sets.length];

			synchronized (this) {
				for (int i = 0, n = sets.length; i < n; ++i) {
					mFutures[i] = sets[i].requestSync(this);
					Log.d(TAG, "  request sync: " + Utils.maskDebugInfo(sets[i].getName()));
				}
			}
		}

		@Override
		public synchronized void cancel() {
			if (mIsCancelled) return;
			mIsCancelled = true;
			for (final Future<Integer> future : mFutures) {
				future.cancel();
			}
			if (mResult < 0) {
				mResult = SYNC_RESULT_CANCELLED;
			}
		}

		@Override
		public synchronized Integer get() {
			waitDone();
			return mResult;
		}

		@Override
		public synchronized boolean isCancelled() {
			return mIsCancelled;
		}

		@Override
		public synchronized boolean isDone() {
			return mPendingCount == 0;
		}

		// SyncListener callback
		@Override
		public void onSyncDone(final MediaSet mediaSet, final int resultCode) {
			SyncListener listener = null;
			synchronized (this) {
				if (resultCode == SYNC_RESULT_ERROR) {
					mResult = SYNC_RESULT_ERROR;
				}
				--mPendingCount;
				if (mPendingCount == 0) {
					listener = mListener;
					notifyAll();
				}
				Log.d(TAG, "onSyncDone: " + Utils.maskDebugInfo(mediaSet.getName()) + " #pending=" + mPendingCount);
			}
			if (listener != null) {
				listener.onSyncDone(MediaSet.this, mResult);
			}
		}

		@Override
		public synchronized void waitDone() {
			try {
				while (!isDone()) {
					wait();
				}
			} catch (final InterruptedException e) {
				Log.d(TAG, "waitDone() interrupted");
			}
		}
	}
}
