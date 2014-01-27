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

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

import android.content.ContentResolver;
import android.net.Uri;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import com.gbnix.imageviewer.app.GalleryApp;

class UriSource extends MediaSource {

	public static final String SCHEME_URI_SET = "uri_set";

	private final PathMatcher mMatcher;

	@SuppressWarnings("unused")
	private static final String TAG = "UriSource";
	private static final String IMAGE_TYPE_PREFIX = "image/";
	private static final String IMAGE_TYPE_ANY = "image/*";

	private static final int URI = 1;
	private static final int URI_SET = 2;

	private final GalleryApp mApplication;

	public UriSource(final GalleryApp context) {
		super("uri");
		mMatcher = new PathMatcher();
		mMatcher.add("/uri/*", URI);
		mMatcher.add("/uri/set/*", URI_SET);
		mApplication = context;
	}

	@Override
	public MediaObject createMediaObject(final Path path) {
		final String path_str = path.toString();
		if (path_str.startsWith("/uri/set/")) {
			final String segment[] = path.split();
			if (segment.length != 4) throw new RuntimeException("bad path: " + path);
			final String uris_str = segment[2];
			final String[] uris_array = uris_str.split(";");
			final int len = uris_array.length;
			final Uri[] uris = new Uri[len];
			for (int i = 0; i < len; i++) {
				try {
					uris[i] = Uri.parse(URLDecoder.decode(uris_array[i], "UTF-8"));
				} catch (final UnsupportedEncodingException e) {
					throw new RuntimeException(e);
				}
			}
			return new UriSet(mApplication, path, uris);
		} else {
			final String segment[] = path.split();
			if (segment.length != 3) throw new RuntimeException("bad path: " + path);
			final String uri, type;
			try {
				uri = URLDecoder.decode(segment[1], "UTF-8");
				type = URLDecoder.decode(segment[2], "UTF-8");
			} catch (final UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
			return new UriImage(mApplication, path, Uri.parse(uri), type);
		}
	}

	@Override
	public Path findPathByUri(final Uri uri, String type) {
		if (SCHEME_URI_SET.equals(uri.getScheme())) {
			final String uris = uri.getHost();
			try {
				final String data_decoded = new String(Base64.decode(uris, Base64.DEFAULT), "UTF-8");
				return Path.fromString("/uri/set/" + data_decoded + "/" + URLEncoder.encode(IMAGE_TYPE_ANY, "UTF-8"));
			} catch (final UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
		}
		final String mimeType = getMimeType(uri);

		// Try to find a most specific type but it has to be started with
		// "image/"
		if (type == null || IMAGE_TYPE_ANY.equals(type) && mimeType.startsWith(IMAGE_TYPE_PREFIX)) {
			type = mimeType;
		}

		try {
			if (type.startsWith(IMAGE_TYPE_PREFIX))
				return Path.fromString("/uri/" + URLEncoder.encode(uri.toString(), "UTF-8") + "/"
						+ URLEncoder.encode(type, "UTF-8"));
		} catch (final UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
		// We have no clues that it is an image
		return null;
	}

	private String getMimeType(final Uri uri) {
		if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
			final String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
			final String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
			if (type != null) return type;
		}
		// Assume the type is image if the type cannot be resolved
		// This could happen for "http" URI.
		String type = mApplication.getContentResolver().getType(uri);
		if (type == null) {
			type = "image/*";
		}
		return type;
	}
}
