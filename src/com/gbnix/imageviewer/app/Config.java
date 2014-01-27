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

import android.content.Context;
import android.content.res.Resources;

import com.gbnix.imageviewer.R;
import com.gbnix.imageviewer.ui.SlotView;

final class Config {
	public static class AlbumPage {
		private static AlbumPage sInstance;

		public SlotView.Spec slotViewSpec;

		private AlbumPage(final Context context) {
			final Resources r = context.getResources();

			slotViewSpec = new SlotView.Spec();
			slotViewSpec.rowsLand = r.getInteger(R.integer.album_rows_land);
			slotViewSpec.rowsPort = r.getInteger(R.integer.album_rows_port);
			slotViewSpec.slotGap = r.getDimensionPixelSize(R.dimen.album_slot_gap);
		}

		public static synchronized AlbumPage get(final Context context) {
			if (sInstance == null) {
				sInstance = new AlbumPage(context);
			}
			return sInstance;
		}
	}

}
