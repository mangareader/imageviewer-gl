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

import java.util.concurrent.atomic.AtomicBoolean;

import android.net.Uri;

import com.gbnix.imageviewer.app.GalleryApp;

// This handles change notification for media sets.
public class ChangeNotifier {

	private final MediaSet mMediaSet;
	private final AtomicBoolean mContentDirty = new AtomicBoolean(true);

	public ChangeNotifier(final MediaSet set, final Uri uri, final GalleryApp application) {
		mMediaSet = set;
		application.getDataManager().registerChangeNotifier(uri, this);
	}

	// For debugging only.
	public void fakeChange() {
		onChange(false);
	}

	// Returns the dirty flag and clear it.
	public boolean isDirty() {
		return mContentDirty.compareAndSet(true, false);
	}

	protected void onChange(final boolean selfChange) {
		if (mContentDirty.compareAndSet(false, true)) {
			mMediaSet.notifyContentChanged();
		}
	}
}