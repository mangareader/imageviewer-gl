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

import android.content.Context;
import android.util.TypedValue;

public class ScrollBarView extends GLView {
	@SuppressWarnings("unused")
	private static final String TAG = "ScrollBarView";

	private int mBarHeight;

	private final int mGripHeight;
	private int mGripPosition; // left side of the grip
	private int mGripWidth; // zero if the grip is disabled
	private final int mGivenGripWidth;

	private int mContentPosition;
	private int mContentTotal;

	private final NinePatchTexture mScrollBarTexture;

	public ScrollBarView(final Context context, final int gripHeight, final int gripWidth) {
		final TypedValue outValue = new TypedValue();
		context.getTheme().resolveAttribute(android.R.attr.scrollbarThumbHorizontal, outValue, true);
		mScrollBarTexture = new NinePatchTexture(context, outValue.resourceId);
		mGripPosition = 0;
		mGripWidth = 0;
		mGivenGripWidth = gripWidth;
		mGripHeight = gripHeight;
	}

	// The content position is between 0 to "total". The current position is
	// in "position".
	public void setContentPosition(final int position, final int total) {
		if (position == mContentPosition && total == mContentTotal) return;

		invalidate();

		mContentPosition = position;
		mContentTotal = total;

		// If the grip cannot move, don't draw it.
		if (mContentTotal <= 0) {
			mGripPosition = 0;
			mGripWidth = 0;
			return;
		}

		// Map from the content range to scroll bar range.
		//
		// mContentTotal --> getWidth() - mGripWidth
		// mContentPosition --> mGripPosition
		mGripWidth = mGivenGripWidth;
		final float r = (getWidth() - mGripWidth) / (float) mContentTotal;
		mGripPosition = Math.round(r * mContentPosition);
	}

	@Override
	protected void onLayout(final boolean changed, final int left, final int top, final int right, final int bottom) {
		if (!changed) return;
		mBarHeight = bottom - top;
	}

	@Override
	protected void render(final GLCanvas canvas) {
		super.render(canvas);
		if (mGripWidth == 0) return;
		final int y = (mBarHeight - mGripHeight) / 2;
		mScrollBarTexture.draw(canvas, mGripPosition, y, mGripWidth, mGripHeight);
	}
}
