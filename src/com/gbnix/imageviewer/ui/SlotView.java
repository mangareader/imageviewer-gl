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
import android.graphics.Rect;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;

import com.gbnix.imageviewer.anim.Animation;
import com.gbnix.imageviewer.app.GalleryActivity;
import com.gbnix.imageviewer.common.Utils;

public class SlotView extends GLView {
	@SuppressWarnings("unused")
	private static final String TAG = "SlotView";

	private static final boolean WIDE = true;
	private static final int INDEX_NONE = -1;

	public static final int RENDER_MORE_PASS = 1;
	public static final int RENDER_MORE_FRAME = 2;

	private final GestureDetector mGestureDetector;

	private final ScrollerHelper mScroller;

	private final Paper mPaper = new Paper();

	private Listener mListener;
	private UserInteractionListener mUIListener;
	private boolean mMoreAnimation = false;

	private SlotAnimation mAnimation = null;
	private final Layout mLayout = new Layout();

	private int mStartIndex = INDEX_NONE;
	// whether the down action happened while the view is scrolling.
	private boolean mDownInScrolling;
	private int mOverscrollEffect = OVERSCROLL_3D;
	private final Handler mHandler;

	private SlotRenderer mRenderer;
	private final int[] mRequestRenderSlots = new int[16];
	public static final int OVERSCROLL_3D = 0;

	public static final int OVERSCROLL_SYSTEM = 1;

	public static final int OVERSCROLL_NONE = 2;

	// to prevent allocating memory
	private final Rect mTempRect = new Rect();

	public SlotView(final GalleryActivity activity, final Spec spec) {
		mGestureDetector = new GestureDetector((Context) activity, new MyGestureListener());
		mScroller = new ScrollerHelper((Context) activity);
		mHandler = new SynchronizedHandler(activity.getGLRoot());
		setSlotSpec(spec);
	}

	@Override
	public void addComponent(final GLView view) {
		throw new UnsupportedOperationException();
	}

	public int getScrollX() {
		return mScrollX;
	}

	public int getScrollY() {
		return mScrollY;
	}

	public Rect getSlotRect(final int slotIndex) {
		return mLayout.getSlotRect(slotIndex, new Rect());
	}

	public int getVisibleEnd() {
		return mLayout.getVisibleEnd();
	}

	public int getVisibleStart() {
		return mLayout.getVisibleStart();
	}

	public void makeSlotVisible(final int index) {
		final Rect rect = mLayout.getSlotRect(index, mTempRect);
		final int visibleBegin = WIDE ? mScrollX : mScrollY;
		final int visibleLength = WIDE ? getWidth() : getHeight();
		final int visibleEnd = visibleBegin + visibleLength;
		final int slotBegin = WIDE ? rect.left : rect.top;
		final int slotEnd = WIDE ? rect.right : rect.bottom;

		int position = visibleBegin;
		if (visibleLength < slotEnd - slotBegin) {
			position = visibleBegin;
		} else if (slotBegin < visibleBegin) {
			position = slotBegin;
		} else if (slotEnd > visibleEnd) {
			position = slotEnd - visibleLength;
		}

		setScrollPosition(position);
	}

	public void setCenterIndex(final int index) {
		final int slotCount = mLayout.mSlotCount;
		if (index < 0 || index >= slotCount) return;
		final Rect rect = mLayout.getSlotRect(index, mTempRect);
		final int position = WIDE ? (rect.left + rect.right - getWidth()) / 2
				: (rect.top + rect.bottom - getHeight()) / 2;
		setScrollPosition(position);
	}

	public void setListener(final Listener listener) {
		mListener = listener;
	}

	public void setOverscrollEffect(final int kind) {
		mOverscrollEffect = kind;
		mScroller.setOverfling(kind == OVERSCROLL_SYSTEM);
	}

	public void setScrollPosition(int position) {
		position = Utils.clamp(position, 0, mLayout.getScrollLimit());
		mScroller.setPosition(position);
		updateScrollPosition(position, false);
	}

	// Return true if the layout parameters have been changed
	public boolean setSlotCount(final int slotCount) {
		final boolean changed = mLayout.setSlotCount(slotCount);

		// mStartIndex is applied the first time setSlotCount is called.
		if (mStartIndex != INDEX_NONE) {
			setCenterIndex(mStartIndex);
			mStartIndex = INDEX_NONE;
		}
		// Reset the scroll position to avoid scrolling over the updated limit.
		setScrollPosition(WIDE ? mScrollX : mScrollY);
		return changed;
	}

	public void setSlotRenderer(final SlotRenderer slotDrawer) {
		mRenderer = slotDrawer;
		if (mRenderer != null) {
			mRenderer.onSlotSizeChanged(mLayout.mSlotWidth, mLayout.mSlotHeight);
			mRenderer.onVisibleRangeChanged(getVisibleStart(), getVisibleEnd());
		}
	}

	public void setSlotSpec(final Spec spec) {
		mLayout.setSlotSpec(spec);
	}

	public void setStartIndex(final int index) {
		mStartIndex = index;
	}

	public void setUserInteractionListener(final UserInteractionListener listener) {
		mUIListener = listener;
	}

	public void startRisingAnimation() {
		mAnimation = new RisingAnimation();
		mAnimation.start();
		if (mLayout.mSlotCount != 0) {
			invalidate();
		}
	}

	public void startScatteringAnimation(final RelativePosition position) {
		mAnimation = new ScatteringAnimation(position);
		mAnimation.start();
		if (mLayout.mSlotCount != 0) {
			invalidate();
		}
	}

	@Override
	protected void onLayout(final boolean changeSize, final int l, final int t, final int r, final int b) {
		if (!changeSize) return;

		// Make sure we are still at a resonable scroll position after the size
		// is changed (like orientation change). We choose to keep the center
		// visible slot still visible. This is arbitrary but reasonable.
		final int visibleIndex = (mLayout.getVisibleStart() + mLayout.getVisibleEnd()) / 2;
		mLayout.setSize(r - l, b - t);
		makeSlotVisible(visibleIndex);
		if (mOverscrollEffect == OVERSCROLL_3D) {
			mPaper.setSize(r - l, b - t);
		}
	}

	protected void onScrollPositionChanged(final int newPosition) {
		final int limit = mLayout.getScrollLimit();
		mListener.onScrollPositionChanged(newPosition, limit);
	}

	@Override
	protected boolean onTouch(final MotionEvent event) {
		if (mUIListener != null) {
			mUIListener.onUserInteraction();
		}
		mGestureDetector.onTouchEvent(event);
		switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN:
				mDownInScrolling = !mScroller.isFinished();
				mScroller.forceFinished();
				break;
			case MotionEvent.ACTION_UP:
				mPaper.onRelease();
				invalidate();
				break;
		}
		return true;
	}

	@Override
	protected void render(final GLCanvas canvas) {
		super.render(canvas);

		if (mRenderer == null) return;
		mRenderer.prepareDrawing();

		final long animTime = AnimationTime.get();
		boolean more = mScroller.advanceAnimation(animTime);
		more |= mLayout.advanceAnimation(animTime);
		final int oldX = mScrollX;
		updateScrollPosition(mScroller.getPosition(), false);

		boolean paperActive = false;
		if (mOverscrollEffect == OVERSCROLL_3D) {
			// Check if an edge is reached and notify mPaper if so.
			final int newX = mScrollX;
			final int limit = mLayout.getScrollLimit();
			if (oldX > 0 && newX == 0 || oldX < limit && newX == limit) {
				float v = mScroller.getCurrVelocity();
				if (newX == limit) {
					v = -v;
				}

				// I don't know why, but getCurrVelocity() can return NaN.
				if (!Float.isNaN(v)) {
					mPaper.edgeReached(v);
				}
			}
			paperActive = mPaper.advanceAnimation();
		}

		more |= paperActive;

		if (mAnimation != null) {
			more |= mAnimation.calculate(animTime);
		}

		canvas.translate(-mScrollX, -mScrollY);

		int requestCount = 0;
		final int requestedSlot[] = expandIntArray(mRequestRenderSlots, mLayout.mVisibleEnd - mLayout.mVisibleStart);

		for (int i = mLayout.mVisibleEnd - 1; i >= mLayout.mVisibleStart; --i) {
			final int r = renderItem(canvas, i, 0, paperActive);
			if ((r & RENDER_MORE_FRAME) != 0) {
				more = true;
			}
			if ((r & RENDER_MORE_PASS) != 0) {
				requestedSlot[requestCount++] = i;
			}
		}

		for (int pass = 1; requestCount != 0; ++pass) {
			int newCount = 0;
			for (int i = 0; i < requestCount; ++i) {
				final int r = renderItem(canvas, requestedSlot[i], pass, paperActive);
				if ((r & RENDER_MORE_FRAME) != 0) {
					more = true;
				}
				if ((r & RENDER_MORE_PASS) != 0) {
					requestedSlot[newCount++] = i;
				}
			}
			requestCount = newCount;
		}

		canvas.translate(mScrollX, mScrollY);

		if (more) {
			invalidate();
		}

		final UserInteractionListener listener = mUIListener;
		if (mMoreAnimation && !more && listener != null) {
			mHandler.post(new Runnable() {
				@Override
				public void run() {
					listener.onUserInteractionEnd();
				}
			});
		}
		mMoreAnimation = more;
	}

	private int renderItem(final GLCanvas canvas, final int index, final int pass, final boolean paperActive) {
		canvas.save(GLCanvas.SAVE_FLAG_ALPHA | GLCanvas.SAVE_FLAG_MATRIX);
		final Rect rect = mLayout.getSlotRect(index, mTempRect);
		if (paperActive) {
			canvas.multiplyMatrix(mPaper.getTransform(rect, mScrollX), 0);
		} else {
			canvas.translate(rect.left, rect.top, 0);
		}
		if (mAnimation != null && mAnimation.isActive()) {
			mAnimation.apply(canvas, index, rect);
		}
		final int result = mRenderer.renderSlot(canvas, index, pass, rect.right - rect.left, rect.bottom - rect.top);
		canvas.restore();
		return result;
	}

	private void updateScrollPosition(final int position, final boolean force) {
		if (!force && (WIDE ? position == mScrollX : position == mScrollY)) return;
		if (WIDE) {
			mScrollX = position;
		} else {
			mScrollY = position;
		}
		mLayout.setScrollPosition(position);
		onScrollPositionChanged(position);
	}

	private static int[] expandIntArray(int array[], final int capacity) {
		while (array.length < capacity) {
			array = new int[array.length * 2];
		}
		return array;
	}

	public class Layout {

		private int mVisibleStart;
		private int mVisibleEnd;

		private int mSlotCount;
		private int mSlotWidth;
		private int mSlotHeight;
		private int mSlotGap;

		private Spec mSpec;

		private int mWidth;
		private int mHeight;

		private int mUnitCount;
		private int mContentLength;
		private int mScrollPosition;

		private final IntegerAnimation mVerticalPadding = new IntegerAnimation();
		private final IntegerAnimation mHorizontalPadding = new IntegerAnimation();

		public boolean advanceAnimation(final long animTime) {
			// use '|' to make sure both sides will be executed
			return mVerticalPadding.calculate(animTime) | mHorizontalPadding.calculate(animTime);
		}

		public int getScrollLimit() {
			final int limit = WIDE ? mContentLength - mWidth : mContentLength - mHeight;
			return limit <= 0 ? 0 : limit;
		}

		public int getSlotHeight() {
			return mSlotHeight;
		}

		public int getSlotIndexByPosition(final float x, final float y) {
			int absoluteX = Math.round(x) + (WIDE ? mScrollPosition : 0);
			int absoluteY = Math.round(y) + (WIDE ? 0 : mScrollPosition);

			absoluteX -= mHorizontalPadding.get();
			absoluteY -= mVerticalPadding.get();

			if (absoluteX < 0 || absoluteY < 0) return INDEX_NONE;

			final int columnIdx = absoluteX / (mSlotWidth + mSlotGap);
			final int rowIdx = absoluteY / (mSlotHeight + mSlotGap);

			if (WIDE && rowIdx >= mUnitCount) return INDEX_NONE;

			if (absoluteX % (mSlotWidth + mSlotGap) >= mSlotWidth) return INDEX_NONE;

			if (absoluteY % (mSlotHeight + mSlotGap) >= mSlotHeight) return INDEX_NONE;

			final int index = WIDE ? columnIdx * mUnitCount + rowIdx : rowIdx * mUnitCount + columnIdx;

			return index >= mSlotCount ? INDEX_NONE : index;
		}

		public Rect getSlotRect(final int index, final Rect rect) {
			int col, row;
			if (WIDE) {
				col = index / mUnitCount;
				row = index - col * mUnitCount;
			} else {
				row = index / mUnitCount;
				col = index - row * mUnitCount;
			}

			final int x = mHorizontalPadding.get() + col * (mSlotWidth + mSlotGap);
			final int y = mVerticalPadding.get() + row * (mSlotHeight + mSlotGap);
			rect.set(x, y, x + mSlotWidth, y + mSlotHeight);
			return rect;
		}

		public int getSlotWidth() {
			return mSlotWidth;
		}

		public int getVisibleEnd() {
			return mVisibleEnd;
		}

		public int getVisibleStart() {
			return mVisibleStart;
		}

		public void setScrollPosition(final int position) {
			if (mScrollPosition == position) return;
			mScrollPosition = position;
			updateVisibleSlotRange();
		}

		public void setSize(final int width, final int height) {
			mWidth = width;
			mHeight = height;
			initLayoutParameters();
		}

		public boolean setSlotCount(final int slotCount) {
			if (slotCount == mSlotCount) return false;
			if (mSlotCount != 0) {
				mHorizontalPadding.setEnabled(true);
				mVerticalPadding.setEnabled(true);
			}
			mSlotCount = slotCount;
			final int hPadding = mHorizontalPadding.getTarget();
			final int vPadding = mVerticalPadding.getTarget();
			initLayoutParameters();
			return vPadding != mVerticalPadding.getTarget() || hPadding != mHorizontalPadding.getTarget();
		}

		public void setSlotSpec(final Spec spec) {
			mSpec = spec;
		}

		private void initLayoutParameters() {
			// Initialize mSlotWidth and mSlotHeight from mSpec
			if (mSpec.slotWidth != -1) {
				mSlotGap = 0;
				mSlotWidth = mSpec.slotWidth;
				mSlotHeight = mSpec.slotHeight;
			} else {
				final int rows = mWidth > mHeight ? mSpec.rowsLand : mSpec.rowsPort;
				mSlotGap = mSpec.slotGap;
				mSlotHeight = Math.max(1, (mHeight - (rows - 1) * mSlotGap) / rows);
				mSlotWidth = mSlotHeight;
			}

			if (mRenderer != null) {
				mRenderer.onSlotSizeChanged(mSlotWidth, mSlotHeight);
			}

			final int[] padding = new int[2];
			if (WIDE) {
				initLayoutParameters(mWidth, mHeight, mSlotWidth, mSlotHeight, padding);
				mVerticalPadding.startAnimateTo(padding[0]);
				mHorizontalPadding.startAnimateTo(padding[1]);
			} else {
				initLayoutParameters(mHeight, mWidth, mSlotHeight, mSlotWidth, padding);
				mVerticalPadding.startAnimateTo(padding[1]);
				mHorizontalPadding.startAnimateTo(padding[0]);
			}
			updateVisibleSlotRange();
		}

		// Calculate
		// (1) mUnitCount: the number of slots we can fit into one column (or
		// row).
		// (2) mContentLength: the width (or height) we need to display all the
		// columns (rows).
		// (3) padding[]: the vertical and horizontal padding we need in order
		// to put the slots towards to the center of the display.
		//
		// The "major" direction is the direction the user can scroll. The other
		// direction is the "minor" direction.
		//
		// The comments inside this method are the description when the major
		// directon is horizontal (X), and the minor directon is vertical (Y).
		private void initLayoutParameters(final int majorLength, final int minorLength, /*
																						 * The
																						 * view
																						 * width
																						 * and
																						 * height
																						 */
				final int majorUnitSize, final int minorUnitSize, /*
																 * The slot
																 * width and
																 * height
																 */
				final int[] padding) {
			int unitCount = (minorLength + mSlotGap) / (minorUnitSize + mSlotGap);
			if (unitCount == 0) {
				unitCount = 1;
			}
			mUnitCount = unitCount;

			// We put extra padding above and below the column.
			final int availableUnits = Math.min(mUnitCount, mSlotCount);
			final int usedMinorLength = availableUnits * minorUnitSize + (availableUnits - 1) * mSlotGap;
			padding[0] = (minorLength - usedMinorLength) / 2;

			// Then calculate how many columns we need for all slots.
			final int count = (mSlotCount + mUnitCount - 1) / mUnitCount;
			mContentLength = count * majorUnitSize + (count - 1) * mSlotGap;

			// If the content length is less then the screen width, put
			// extra padding in left and right.
			padding[1] = Math.max(0, (majorLength - mContentLength) / 2);
		}

		private void setVisibleRange(final int start, final int end) {
			if (start == mVisibleStart && end == mVisibleEnd) return;
			if (start < end) {
				mVisibleStart = start;
				mVisibleEnd = end;
			} else {
				mVisibleStart = mVisibleEnd = 0;
			}
			if (mRenderer != null) {
				mRenderer.onVisibleRangeChanged(mVisibleStart, mVisibleEnd);
			}
		}

		private void updateVisibleSlotRange() {
			final int position = mScrollPosition;

			if (WIDE) {
				final int startCol = position / (mSlotWidth + mSlotGap);
				final int start = Math.max(0, mUnitCount * startCol);
				final int endCol = (position + mWidth + mSlotWidth + mSlotGap - 1) / (mSlotWidth + mSlotGap);
				final int end = Math.min(mSlotCount, mUnitCount * endCol);
				setVisibleRange(start, end);
			} else {
				final int startRow = position / (mSlotHeight + mSlotGap);
				final int start = Math.max(0, mUnitCount * startRow);
				final int endRow = (position + mHeight + mSlotHeight + mSlotGap - 1) / (mSlotHeight + mSlotGap);
				final int end = Math.min(mSlotCount, mUnitCount * endRow);
				setVisibleRange(start, end);
			}
		}
	}

	public interface Listener {
		public void onDown(int index);

		public void onLongTap(int index);

		public void onScrollPositionChanged(int position, int total);

		public void onSingleTapUp(int index);

		public void onUp(boolean followedByLongPress);
	}

	public static class RisingAnimation extends SlotAnimation {
		private static final int RISING_DISTANCE = 128;

		@Override
		public void apply(final GLCanvas canvas, final int slotIndex, final Rect target) {
			canvas.translate(0, 0, RISING_DISTANCE * (1 - mProgress));
		}
	}

	public static class ScatteringAnimation extends SlotAnimation {
		private final int PHOTO_DISTANCE = 1000;
		private final RelativePosition mCenter;

		public ScatteringAnimation(final RelativePosition center) {
			mCenter = center;
		}

		@Override
		public void apply(final GLCanvas canvas, final int slotIndex, final Rect target) {
			canvas.translate((mCenter.getX() - target.centerX()) * (1 - mProgress), (mCenter.getY() - target.centerY())
					* (1 - mProgress), slotIndex * PHOTO_DISTANCE * (1 - mProgress));
			canvas.setAlpha(mProgress);
		}
	}

	public static class SimpleListener implements Listener {
		@Override
		public void onDown(final int index) {
		}

		@Override
		public void onLongTap(final int index) {
		}

		@Override
		public void onScrollPositionChanged(final int position, final int total) {
		}

		@Override
		public void onSingleTapUp(final int index) {
		}

		@Override
		public void onUp(final boolean followedByLongPress) {
		}
	}

	public static abstract class SlotAnimation extends Animation {
		protected float mProgress = 0;

		public SlotAnimation() {
			setInterpolator(new DecelerateInterpolator(4));
			setDuration(1500);
		}

		abstract public void apply(GLCanvas canvas, int slotIndex, Rect target);

		@Override
		protected void onCalculate(final float progress) {
			mProgress = progress;
		}
	}

	public static interface SlotRenderer {
		public void onSlotSizeChanged(int width, int height);

		public void onVisibleRangeChanged(int visibleStart, int visibleEnd);

		public void prepareDrawing();

		public int renderSlot(GLCanvas canvas, int index, int pass, int width, int height);
	}

	// This Spec class is used to specify the size of each slot in the SlotView.
	// There are two ways to do it:
	//
	// (1) Specify slotWidth and slotHeight: they specify the width and height
	// of each slot. The number of rows and the gap between slots will be
	// determined automatically.
	// (2) Specify rowsLand, rowsPort, and slotGap: they specify the number
	// of rows in landscape/portrait mode and the gap between slots. The
	// width and height of each slot is determined automatically.
	//
	// The initial value of -1 means they are not specified.
	public static class Spec {
		public int slotWidth = -1;
		public int slotHeight = -1;

		public int rowsLand = -1;
		public int rowsPort = -1;
		public int slotGap = -1;
	}

	private static class IntegerAnimation extends Animation {
		private int mTarget;
		private int mCurrent = 0;
		private int mFrom = 0;
		private boolean mEnabled = false;

		public int get() {
			return mCurrent;
		}

		public int getTarget() {
			return mTarget;
		}

		public void setEnabled(final boolean enabled) {
			mEnabled = enabled;
		}

		public void startAnimateTo(final int target) {
			if (!mEnabled) {
				mTarget = mCurrent = target;
				return;
			}
			if (target == mTarget) return;

			mFrom = mCurrent;
			mTarget = target;
			setDuration(180);
			start();
		}

		@Override
		protected void onCalculate(final float progress) {
			mCurrent = Math.round(mFrom + progress * (mTarget - mFrom));
			if (progress == 1f) {
				mEnabled = false;
			}
		}
	}

	private class MyGestureListener implements GestureDetector.OnGestureListener {
		private boolean isDown;

		@Override
		public boolean onDown(final MotionEvent e) {
			return false;
		}

		@Override
		public boolean onFling(final MotionEvent e1, final MotionEvent e2, final float velocityX, final float velocityY) {
			cancelDown(false);
			final int scrollLimit = mLayout.getScrollLimit();
			if (scrollLimit == 0) return false;
			final float velocity = WIDE ? velocityX : velocityY;
			mScroller.fling((int) -velocity, 0, scrollLimit);
			if (mUIListener != null) {
				mUIListener.onUserInteractionBegin();
			}
			invalidate();
			return true;
		}

		@Override
		public void onLongPress(final MotionEvent e) {
			cancelDown(true);
			if (mDownInScrolling) return;
			lockRendering();
			try {
				final int index = mLayout.getSlotIndexByPosition(e.getX(), e.getY());
				if (index != INDEX_NONE) {
					mListener.onLongTap(index);
				}
			} finally {
				unlockRendering();
			}
		}

		@Override
		public boolean onScroll(final MotionEvent e1, final MotionEvent e2, final float distanceX, final float distanceY) {
			cancelDown(false);
			final float distance = WIDE ? distanceX : distanceY;
			final int overDistance = mScroller.startScroll(Math.round(distance), 0, mLayout.getScrollLimit());
			if (mOverscrollEffect == OVERSCROLL_3D && overDistance != 0) {
				mPaper.overScroll(overDistance);
			}
			invalidate();
			return true;
		}

		// We call the listener's onDown() when our onShowPress() is called and
		// call the listener's onUp() when we receive any further event.
		@Override
		public void onShowPress(final MotionEvent e) {
			final GLRoot root = getGLRoot();
			root.lockRenderThread();
			try {
				if (isDown) return;
				final int index = mLayout.getSlotIndexByPosition(e.getX(), e.getY());
				if (index != INDEX_NONE) {
					isDown = true;
					mListener.onDown(index);
				}
			} finally {
				root.unlockRenderThread();
			}
		}

		@Override
		public boolean onSingleTapUp(final MotionEvent e) {
			cancelDown(false);
			if (mDownInScrolling) return true;
			final int index = mLayout.getSlotIndexByPosition(e.getX(), e.getY());
			if (index != INDEX_NONE) {
				mListener.onSingleTapUp(index);
			}
			return true;
		}

		private void cancelDown(final boolean byLongPress) {
			if (!isDown) return;
			isDown = false;
			mListener.onUp(byLongPress);
		}
	}
}
