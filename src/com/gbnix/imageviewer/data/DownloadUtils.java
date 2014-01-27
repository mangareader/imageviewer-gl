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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import com.gbnix.imageviewer.common.Utils;
import com.gbnix.imageviewer.util.ThreadPool.CancelListener;
import com.gbnix.imageviewer.util.ThreadPool.JobContext;

public class DownloadUtils {
	private static final String TAG = "DownloadService";

	public static boolean download(final JobContext jc, final URL url, final OutputStream output) {
		InputStream input = null;
		try {
			input = openInputStream(url);
			dump(jc, input, output);
			return true;
		} catch (final Throwable t) {
			Log.w(TAG, "fail to download", t);
			return false;
		} finally {
			Utils.closeSilently(input);
		}
	}

	public static void dump(final JobContext jc, final InputStream is, final OutputStream os) throws IOException {
		final byte buffer[] = new byte[4096];
		int rc = is.read(buffer, 0, buffer.length);
		final Thread thread = Thread.currentThread();
		jc.setCancelListener(new CancelListener() {
			@Override
			public void onCancel() {
				thread.interrupt();
			}
		});
		while (rc > 0) {
			if (jc.isCancelled()) throw new InterruptedIOException();
			os.write(buffer, 0, rc);
			rc = is.read(buffer, 0, buffer.length);
		}
		jc.setCancelListener(null);
		Thread.interrupted(); // consume the interrupt signal
	}

	public static boolean requestDownload(final JobContext jc, final URL url, final File file) {
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(file);
			return download(jc, url, fos);
		} catch (final Throwable t) {
			return false;
		} finally {
			Utils.closeSilently(fos);
		}
	}
	
	private static InputStream openInputStream(URL url) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		int respCode = conn.getResponseCode();
		while (respCode == 301 || respCode == 302) {
			final String loc = conn.getHeaderField("Location");
			conn = (HttpURLConnection) new URL(loc).openConnection();
			respCode = conn.getResponseCode();
		}
		return conn.getInputStream();
	}
}