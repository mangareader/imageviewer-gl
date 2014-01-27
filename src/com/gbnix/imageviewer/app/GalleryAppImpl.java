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

import java.io.File;

import android.app.Application;
import android.content.Context;

import com.gbnix.imageviewer.data.DataManager;
import com.gbnix.imageviewer.data.DownloadCache;
import com.gbnix.imageviewer.data.ImageCacheService;
import com.gbnix.imageviewer.util.GalleryUtils;
import com.gbnix.imageviewer.util.ThreadPool;

public class GalleryAppImpl extends Application implements GalleryApp {

	private static final String DOWNLOAD_FOLDER = "download";
	private static final long DOWNLOAD_CAPACITY = 64 * 1024 * 1024; // 64M

	private ImageCacheService mImageCacheService;
	private final Object mLock = new Object();
	private DataManager mDataManager;
	private ThreadPool mThreadPool;
	private DownloadCache mDownloadCache;

	@Override
	public Context getAndroidContext() {
		return this;
	}

	@Override
	public synchronized DataManager getDataManager() {
		if (mDataManager == null) {
			mDataManager = new DataManager(this);
			mDataManager.initializeSourceMap();
		}
		return mDataManager;
	}

	@Override
	public synchronized DownloadCache getDownloadCache() {
		if (mDownloadCache == null) {
			final File cacheDir = new File(getExternalCacheDir(), DOWNLOAD_FOLDER);

			if (!cacheDir.isDirectory()) {
				cacheDir.mkdirs();
			}

			if (!cacheDir.isDirectory()) throw new RuntimeException("fail to create: " + cacheDir.getAbsolutePath());
			mDownloadCache = new DownloadCache(this, cacheDir, DOWNLOAD_CAPACITY);
		}
		return mDownloadCache;
	}

	@Override
	public ImageCacheService getImageCacheService() {
		// This method may block on file I/O so a dedicated lock is needed here.
		synchronized (mLock) {
			if (mImageCacheService == null) {
				mImageCacheService = new ImageCacheService(getAndroidContext());
			}
			return mImageCacheService;
		}
	}

	@Override
	public synchronized ThreadPool getThreadPool() {
		if (mThreadPool == null) {
			mThreadPool = new ThreadPool();
		}
		return mThreadPool;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		GalleryUtils.initialize(this);
	}
}
