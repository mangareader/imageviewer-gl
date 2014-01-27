/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.Window;
import android.widget.Toast;

import com.gbnix.imageviewer.R;
import com.gbnix.imageviewer.common.Utils;
import com.gbnix.imageviewer.data.Path;

public final class Gallery extends AbstractGalleryActivity {

	public static final String EXTRA_URIS = "uris";

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		super.onCreateOptionsMenu(menu);
		return getStateManager().createOptionsMenu(menu);
	}

	/**
	 * This method was called when you chanage selected photo.
	 */
	@Override
	public void onPhotoChanged(final int index, final Path item) {
		Toast.makeText(this, String.format("onPhotoChanged(%d)", index),
				Toast.LENGTH_SHORT).show();
	}

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_ACTION_BAR);
		requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);

		setContentView(R.layout.main);

		if (savedInstanceState != null) {
			getStateManager().restoreFromState(savedInstanceState);
		} else {
			final String[] uris_string = {
					"http://s6.haivl.com/data/photos2/20140126/88e8a6d0ad154a53a486aeb17dad86a0/medium-22d5efb18bb84f8e8a7c596106d58f23-400.jpg",
					"http://s4.haivl.com/data/photos2/20140127/1322b6ecd34c458aabaab95a422a8bc8/medium-9505ed5669fb43d198d7c8672c90425e-400.jpg",
					"http://s4.haivl.com/data/photos2/20140127/f7263c0776e94b20ab934200c0fb5eaf/medium-141f0f7f84f1434387688109f44850fb-400.jpg",
					"http://s6.haivl.com/data/photos2/20140127/ad79941df0d24eb69ace03a9d98a1c7f/medium-3dc564e915ab422081cf4e3afb8ff05b-400.jpg" };

			final ArrayList<Uri> uris = new ArrayList<Uri>();
			for (final String uri_string : uris_string) {
				uris.add(Uri.parse(uri_string));
			}
			setIntent(new Intent().putExtra(EXTRA_URIS, uris));
			initializeByIntent(getIntent());
		}
	}

	@Override
	protected void onResume() {
		Utils.assertTrue(getStateManager().getStateCount() > 0);
		super.onResume();
	}

	private void initializeByIntent(final Intent intent) {
		final Bundle data = new Bundle();
		final ArrayList<Uri> uris = intent
				.getParcelableArrayListExtra(EXTRA_URIS);
		if (uris == null || uris.isEmpty()) {
			// No image specified.
			finish();
			return;
		}
		data.putParcelableArrayList(EXTRA_URIS, uris);
		getStateManager().startState(PhotoPage.class, data);
	}

}
