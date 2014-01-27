/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.gbnix.imageviewer.common;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.apache.http.HttpVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;

/**
 * Constructs {@link HttpClient} instances and isolates client code from API
 * level differences.
 */
public final class HttpClientFactory {
	private static String sUserAgent = null;

	private HttpClientFactory() {
	}

	/**
	 * Closes an HttpClient.
	 */
	public static void close(final HttpClient client) {
		// AndroidHttpClient is available on all platform releases,
		// but is hidden until API Level 8
		try {
			final Class<?> clazz = client.getClass();
			final Method method = clazz.getMethod("close", (Class<?>[]) null);
			method.invoke(client, (Object[]) null);
		} catch (final InvocationTargetException e) {
			throw new RuntimeException(e);
		} catch (final NoSuchMethodException e) {
			throw new RuntimeException(e);
		} catch (final IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	// TODO: migrate GDataClient to use this util method instead of apache's
	// DefaultHttpClient.
	/**
	 * Creates an HttpClient with the userAgent string constructed from the
	 * package name contained in the context.
	 * 
	 * @return the client
	 */
	public static HttpClient newHttpClient(final Context context) {
		return HttpClientFactory.newHttpClient(getUserAgent(context));
	}

	/**
	 * Creates an HttpClient with the specified userAgent string.
	 * 
	 * @param userAgent the userAgent string
	 * @return the client
	 */
	public static HttpClient newHttpClient(final String userAgent) {
		// AndroidHttpClient is available on all platform releases,
		// but is hidden until API Level 8
		try {
			final Class<?> clazz = Class.forName("android.net.http.AndroidHttpClient");
			final Method newInstance = clazz.getMethod("newInstance", String.class);
			final Object instance = newInstance.invoke(null, userAgent);

			final HttpClient client = (HttpClient) instance;

			// ensure we default to HTTP 1.1
			final HttpParams params = client.getParams();
			params.setParameter(CoreProtocolPNames.PROTOCOL_VERSION, HttpVersion.HTTP_1_1);

			// AndroidHttpClient sets these two parameters thusly by default:
			// HttpConnectionParams.setSoTimeout(params, 60 * 1000);
			// HttpConnectionParams.setConnectionTimeout(params, 60 * 1000);

			// however it doesn't set this one...
			ConnManagerParams.setTimeout(params, 60 * 1000);

			return client;
		} catch (final InvocationTargetException e) {
			throw new RuntimeException(e);
		} catch (final ClassNotFoundException e) {
			throw new RuntimeException(e);
		} catch (final NoSuchMethodException e) {
			throw new RuntimeException(e);
		} catch (final IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	private static String getUserAgent(final Context context) {
		if (sUserAgent == null) {
			PackageInfo pi;
			try {
				pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			} catch (final NameNotFoundException e) {
				throw new IllegalStateException("getPackageInfo failed");
			}
			sUserAgent = String.format("%s/%s; %s/%s/%s/%s; %s/%s/%s", pi.packageName, pi.versionName, Build.BRAND,
					Build.DEVICE, Build.MODEL, Build.ID, Build.VERSION.SDK_INT, Build.VERSION.RELEASE,
					Build.VERSION.INCREMENTAL);
		}
		return sUserAgent;
	}
}
