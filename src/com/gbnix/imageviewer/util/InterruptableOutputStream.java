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

package com.gbnix.imageviewer.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;

import com.gbnix.imageviewer.common.Utils;

public class InterruptableOutputStream extends OutputStream {

	private static final int MAX_WRITE_BYTES = 4096;

	private final OutputStream mOutputStream;
	private volatile boolean mIsInterrupted = false;

	public InterruptableOutputStream(final OutputStream outputStream) {
		mOutputStream = Utils.checkNotNull(outputStream);
	}

	@Override
	public void close() throws IOException {
		mOutputStream.close();
	}

	@Override
	public void flush() throws IOException {
		if (mIsInterrupted) throw new InterruptedIOException();
		mOutputStream.flush();
	}

	public void interrupt() {
		mIsInterrupted = true;
	}

	@Override
	public void write(final byte[] buffer, int offset, final int count) throws IOException {
		final int end = offset + count;
		while (offset < end) {
			if (mIsInterrupted) throw new InterruptedIOException();
			final int bytesCount = Math.min(MAX_WRITE_BYTES, end - offset);
			mOutputStream.write(buffer, offset, bytesCount);
			offset += bytesCount;
		}
	}

	@Override
	public void write(final int oneByte) throws IOException {
		if (mIsInterrupted) throw new InterruptedIOException();
		mOutputStream.write(oneByte);
	}
}
