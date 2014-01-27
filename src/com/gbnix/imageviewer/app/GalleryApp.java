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

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.Looper;

import com.gbnix.imageviewer.data.DataManager;
import com.gbnix.imageviewer.data.DownloadCache;
import com.gbnix.imageviewer.data.ImageCacheService;
import com.gbnix.imageviewer.util.ThreadPool;

public interface GalleryApp {
	public Context getAndroidContext();

	public ContentResolver getContentResolver();

	public DataManager getDataManager();

	public DownloadCache getDownloadCache();

	public ImageCacheService getImageCacheService();

	public Looper getMainLooper();

	public Resources getResources();

	public ThreadPool getThreadPool();
}
