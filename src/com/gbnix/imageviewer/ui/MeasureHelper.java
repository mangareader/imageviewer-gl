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

package com.gbnix.imageviewer.ui;

import android.graphics.Rect;
import android.view.View.MeasureSpec;

class MeasureHelper {

	private static MeasureHelper sInstance = new MeasureHelper(null);

	private GLView mComponent;
	private int mPreferredWidth;
	private int mPreferredHeight;

	private MeasureHelper(final GLView component) {
		mComponent = component;
	}

	public void measure(final int widthSpec, final int heightSpec) {
		final Rect p = mComponent.getPaddings();
		setMeasuredSize(getLength(widthSpec, mPreferredWidth + p.left + p.right),
				getLength(heightSpec, mPreferredHeight + p.top + p.bottom));
	}

	public MeasureHelper setPreferredContentSize(final int width, final int height) {
		mPreferredWidth = width;
		mPreferredHeight = height;
		return this;
	}

	protected void setMeasuredSize(final int width, final int height) {
		mComponent.setMeasuredSize(width, height);
	}

	public static MeasureHelper getInstance(final GLView component) {
		sInstance.mComponent = component;
		return sInstance;
	}

	private static int getLength(final int measureSpec, final int prefered) {
		final int specLength = MeasureSpec.getSize(measureSpec);
		switch (MeasureSpec.getMode(measureSpec)) {
			case MeasureSpec.EXACTLY:
				return specLength;
			case MeasureSpec.AT_MOST:
				return Math.min(prefered, specLength);
			default:
				return prefered;
		}
	}

}
