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
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Message;
import android.util.FloatMath;
import android.view.MotionEvent;
import android.view.animation.AccelerateInterpolator;

import com.gbnix.imageviewer.R;
import com.gbnix.imageviewer.app.GalleryActivity;
import com.gbnix.imageviewer.common.Utils;
import com.gbnix.imageviewer.data.MediaItem;
import com.gbnix.imageviewer.data.MediaObject;
import com.gbnix.imageviewer.data.Path;
import com.gbnix.imageviewer.util.GalleryUtils;
import com.gbnix.imageviewer.util.RangeArray;

public class PhotoView extends GLView {
	private static final String TAG = "PhotoView";
	private static final int PLACEHOLDER_COLOR = 0xFF222222;

	public static final int INVALID_SIZE = -1;
	public static final long INVALID_DATA_VERSION = MediaObject.INVALID_DATA_VERSION;

	// The rules about orientation locking:
	//
	// (1) We need to lock the orientation if we are in page mode camera
	// preview, so there is no (unwanted) rotation animation when the user
	// rotates the device.
	//
	// (2) We need to unlock the orientation if we want to show the action bar
	// because the action bar follows the system orientation.
	//
	// The rules about action bar:
	//
	// (1) If we are in film mode, we don't show action bar.
	//
	// (2) If we go from camera to gallery with capture animation, we show
	// action bar.
	private static final int MSG_CANCEL_EXTRA_SCALING = 2;

	private static final int MSG_SWITCH_FOCUS = 3;

	private static final int MSG_CAPTURE_ANIMATION_DONE = 4;

	private static final int MSG_DELETE_ANIMATION_DONE = 5;
	private static final int MSG_DELETE_DONE = 6;
	private static final int MSG_UNDO_BAR_TIMEOUT = 7;
	private static final int MSG_UNDO_BAR_FULL_CAMERA = 8;
	private static final int MOVE_THRESHOLD = 256;
	private static final float SWIPE_THRESHOLD = 300f;
	private static final float DEFAULT_TEXT_SIZE = 20;

	private static float TRANSITION_SCALE_FACTOR = 0.74f;

	// whether we want to apply card deck effect in page mode.
	private static final boolean CARD_EFFECT = true;
	// whether we want to apply offset effect in film mode.
	private static final boolean OFFSET_EFFECT = true;
	// Used to calculate the scaling factor for the card deck effect.
	private final ZInterpolator mScaleInterpolator = new ZInterpolator(0.5f);

	// Used to calculate the alpha factor for the fading animation.
	private final AccelerateInterpolator mAlphaInterpolator = new AccelerateInterpolator(
			0.9f);

	// We keep this many previous ScreenNails. (also this many next ScreenNails)
	public static final int SCREEN_NAIL_MAX = 3;

	// These are constants for the delete gesture.
	private static final int SWIPE_ESCAPE_VELOCITY = 500; // dp/sec

	private static final int MAX_DISMISS_VELOCITY = 2000; // dp/sec

	// The picture entries, the valid index is from -SCREEN_NAIL_MAX to
	// SCREEN_NAIL_MAX.
	private final RangeArray<Picture> mPictures = new RangeArray<Picture>(
			-SCREEN_NAIL_MAX, SCREEN_NAIL_MAX);

	private final Size[] mSizes = new Size[2 * SCREEN_NAIL_MAX + 1];
	private final MyGestureListener mGestureListener;

	private final GestureRecognizer mGestureRecognizer;
	private final PositionController mPositionController;

	private Listener mListener;
	private Model mModel;

	private TileImageView mTileView;
	private EdgeView mEdgeView;
	private Texture mFallbackImage;
	private int mFallbackImageWidth, mFallbackImageHeight;
	private ProgressSpinner mLoadingIcon;
	private int mLoadingIconWidth, mLoadingIconHeight;
	private SynchronizedHandler mHandler;
	private boolean mCancelExtraScalingPending;

	private boolean mFilmMode = false;

	private int mDisplayRotation = 0;
	private int mCompensation = 0;
	private boolean mFullScreenCamera;
	private final Rect mCameraRelativeFrame = new Rect();
	private final Rect mCameraRect = new Rect();
	// [mPrevBound, mNextBound] is the range of index for all pictures in the
	// model, if we assume the index of current focused picture is 0. So if
	// there are some previous pictures, mPrevBound < 0, and if there are some
	// next pictures, mNextBound > 0.
	private int mPrevBound;
	private int mNextBound;
	// This variable prevents us doing snapback until its values goes to 0. This
	// happens if the user gesture is still in progress or we are in a capture
	// animation.
	private int mHolding;

	private static final int HOLD_TOUCH_DOWN = 1;
	private static final int HOLD_CAPTURE_ANIMATION = 2;

	private static final int HOLD_DELETE = 4;
	// mTouchBoxIndex is the index of the box that is touched by the down
	// gesture in film mode. The value Integer.MAX_VALUE means no box was
	// touched.
	private int mTouchBoxIndex = Integer.MAX_VALUE;
	// Whether the box indicated by mTouchBoxIndex is deletable. Only meaningful
	// if mTouchBoxIndex is not Integer.MAX_VALUE.
	private boolean mTouchBoxDeletable;
	// This is the index of the last deleted item. This is only used as a hint
	// to hide the undo button when we are too far away from the deleted
	// item. The value Integer.MAX_VALUE means there is no such hint.
	private int mUndoIndexHint = Integer.MAX_VALUE;

	private int mUndoBarState;
	private static final int UNDO_BAR_SHOW = 1;
	private static final int UNDO_BAR_TIMEOUT = 2;

	private static final int UNDO_BAR_TOUCHED = 4;

	private static final int UNDO_BAR_FULL_CAMERA = 8;

	private static final int UNDO_BAR_DELETE_LAST = 16;;

	// //////////////////////////////////////////////////////////////////////////
	// Data/Image change notifications
	// //////////////////////////////////////////////////////////////////////////

	public PhotoView(final GalleryActivity activity) {
		mTileView = new TileImageView(activity);
		addComponent(mTileView);
		final Context context = activity.getAndroidContext();
		mEdgeView = new EdgeView(context);
		addComponent(mEdgeView);
		mFallbackImage = new ResourceTexture(context, R.drawable.fallback_image);
		mFallbackImageWidth = mFallbackImage.getWidth();
		mFallbackImageHeight = mFallbackImage.getHeight();

		mHandler = new MyHandler(activity.getGLRoot());

		mGestureListener = new MyGestureListener();
		mGestureRecognizer = new GestureRecognizer(context, mGestureListener);

		mPositionController = new PositionController(context,
				new PositionController.Listener() {
					@Override
					public void invalidate() {
						PhotoView.this.invalidate();
					}

					@Override
					public boolean isHoldingDelete() {
						return (mHolding & HOLD_DELETE) != 0;
					}

					@Override
					public boolean isHoldingDown() {
						return (mHolding & HOLD_TOUCH_DOWN) != 0;
					}

					@Override
					public void onAbsorb(final int velocity, final int direction) {
						mEdgeView.onAbsorb(velocity, direction);
					}

					@Override
					public void onPull(final int offset, final int direction) {
						mEdgeView.onPull(offset, direction);
					}

					@Override
					public void onRelease() {
						mEdgeView.onRelease();
					}
				});
		mLoadingIcon = new ProgressSpinner(context);
		mLoadingIconWidth = mLoadingIcon.getWidth();
		mLoadingIconHeight = mLoadingIcon.getHeight();
		for (int i = -SCREEN_NAIL_MAX; i <= SCREEN_NAIL_MAX; i++) {
			if (i == 0) {
				mPictures.put(i, new FullPicture());
			} else {
				mPictures.put(i, new ScreenNailPicture(i));
			}
		}
	}

	public boolean getFilmMode() {
		return mFilmMode;
	}

	public Rect getPhotoRect(final int index) {
		return mPositionController.getPosition(index);
	}

	public boolean isDeleting() {
		return (mHolding & HOLD_DELETE) != 0
				&& mPositionController.hasDeletingBox();
	}

	public void notifyDataChange(final int[] fromIndex, final int prevBound,
			final int nextBound) {
		mPrevBound = prevBound;
		mNextBound = nextBound;

		// Update mTouchBoxIndex
		if (mTouchBoxIndex != Integer.MAX_VALUE) {
			final int k = mTouchBoxIndex;
			mTouchBoxIndex = Integer.MAX_VALUE;
			for (int i = 0; i < 2 * SCREEN_NAIL_MAX + 1; i++) {
				if (fromIndex[i] == k) {
					mTouchBoxIndex = i - SCREEN_NAIL_MAX;
					break;
				}
			}
		}

		// Hide undo button if we are too far away
		if (mUndoIndexHint != Integer.MAX_VALUE) {
			if (Math.abs(mUndoIndexHint - mModel.getCurrentIndex()) >= 3) {
				hideUndoBar();
			}
		}

		// Update the ScreenNails.
		for (int i = -SCREEN_NAIL_MAX; i <= SCREEN_NAIL_MAX; i++) {
			final Picture p = mPictures.get(i);
			p.reload();
			mSizes[i + SCREEN_NAIL_MAX] = p.getSize();
		}

		final boolean wasDeleting = mPositionController.hasDeletingBox();

		// Move the boxes
		mPositionController.moveBox(fromIndex, mPrevBound < 0, mNextBound > 0,
				mModel.isCamera(0), mSizes);

		for (int i = -SCREEN_NAIL_MAX; i <= SCREEN_NAIL_MAX; i++) {
			setPictureSize(i);
		}

		final boolean isDeleting = mPositionController.hasDeletingBox();

		// If the deletion is done, make HOLD_DELETE persist for only the time
		// needed for a snapback animation.
		if (wasDeleting && !isDeleting) {
			mHandler.removeMessages(MSG_DELETE_DONE);
			final Message m = mHandler.obtainMessage(MSG_DELETE_DONE);
			mHandler.sendMessageDelayed(m,
					PositionController.SNAPBACK_ANIMATION_TIME);
		}

		invalidate();
	}

	public void notifyImageChange(final int index) {
		if (index == 0) {
			mListener.onCurrentImageUpdated();
		}
		mPictures.get(index).reload();
		setPictureSize(index);
		invalidate();
	}

	public void pause() {
		mPositionController.skipAnimation();
		mTileView.freeTextures();
		for (int i = -SCREEN_NAIL_MAX; i <= SCREEN_NAIL_MAX; i++) {
			mPictures.get(i).setScreenNail(null);
		}
		hideUndoBar();
	}

	// move to the camera preview and show controls after resume
	public void resetToFirstPicture() {
		mModel.moveTo(0);
		setFilmMode(false);
	}

	// //////////////////////////////////////////////////////////////////////////
	// Pictures
	// //////////////////////////////////////////////////////////////////////////

	public void resume() {
		mTileView.prepareTextures();
	};

	public void setCameraRelativeFrame(final Rect frame) {
		mCameraRelativeFrame.set(frame);
		updateCameraRect();
		// Originally we do
		// mPositionController.setConstrainedFrame(mCameraRect);
		// here, but it is moved to a parameter of the setImageSize() call, so
		// it can be updated atomically with the CameraScreenNail's size change.
	}

	public void setListener(final Listener listener) {
		mListener = listener;
	}

	public void setModel(final Model model) {
		mModel = model;
		mTileView.setModel(mModel);
	}

	public void setOpenAnimationRect(final Rect rect) {
		mPositionController.setOpenAnimationRect(rect);
	}

	public void setSwipingEnabled(final boolean enabled) {
		mGestureListener.setSwipingEnabled(enabled);
	}

	public boolean switchWithCaptureAnimation(final int offset) {
		final GLRoot root = getGLRoot();
		root.lockRenderThread();
		try {
			return switchWithCaptureAnimationLocked(offset);
		} finally {
			root.unlockRenderThread();
		}
	}

	// //////////////////////////////////////////////////////////////////////////
	// Gestures Handling
	// //////////////////////////////////////////////////////////////////////////

	@Override
	protected void onLayout(final boolean changeSize, final int left,
			final int top, final int right, final int bottom) {
		final int w = right - left;
		final int h = bottom - top;
		mTileView.layout(0, 0, w, h);
		mEdgeView.layout(0, 0, w, h);

		final GLRoot root = getGLRoot();
		final int displayRotation = root.getDisplayRotation();
		final int compensation = root.getCompensation();
		if (mDisplayRotation != displayRotation
				|| mCompensation != compensation) {
			mDisplayRotation = displayRotation;
			mCompensation = compensation;

			// We need to change the size and rotation of the Camera ScreenNail,
			// but we don't want it to animate because the size doen't actually
			// change in the eye of the user.
			for (int i = -SCREEN_NAIL_MAX; i <= SCREEN_NAIL_MAX; i++) {
				final Picture p = mPictures.get(i);
				if (p.isCamera()) {
					p.forceSize();
				}
			}
		}

		updateCameraRect();
		mPositionController.setConstrainedFrame(mCameraRect);
		if (changeSize) {
			mPositionController.setViewSize(getWidth(), getHeight());
		}
	}

	@Override
	protected boolean onTouch(final MotionEvent event) {
		mGestureRecognizer.onTouchEvent(event);
		return true;
	}

	@Override
	protected void render(final GLCanvas canvas) {
		// Check if the camera preview occupies the full screen.
		final boolean full = !mFilmMode && mPictures.get(0).isCamera()
				&& mPositionController.isCenter()
				&& mPositionController.isAtMinimalScale();
		if (full != mFullScreenCamera) {
			mFullScreenCamera = full;
			mListener.onFullScreenChanged(full);
			if (full) {
				mHandler.sendEmptyMessage(MSG_UNDO_BAR_FULL_CAMERA);
			}
		}

		// Determine how many photos we need to draw in addition to the center
		// one.
		int neighbors;
		if (mFullScreenCamera) {
			neighbors = 0;
		} else {
			// In page mode, we draw only one previous/next photo. But if we are
			// doing capture animation, we want to draw all photos.
			final boolean inPageMode = mPositionController.getFilmRatio() == 0f;
			final boolean inCaptureAnimation = (mHolding & HOLD_CAPTURE_ANIMATION) != 0;
			if (inPageMode && !inCaptureAnimation) {
				neighbors = 1;
			} else {
				neighbors = SCREEN_NAIL_MAX;
			}
		}

		// Draw photos from back to front
		for (int i = neighbors; i >= -neighbors; i--) {
			final Rect r = mPositionController.getPosition(i);
			mPictures.get(i).draw(canvas, r);
		}

		renderChild(canvas, mEdgeView);

		mPositionController.advanceAnimation();
		checkFocusSwitching();
	}

	// Returns true if the user can still undo the deletion of the last
	// remaining picture in the album. We need to check this and delay making
	// the camera preview full screen, otherwise the user won't have a chance to
	// undo it.
	private boolean canUndoLastPicture() {
		if ((mUndoBarState & UNDO_BAR_SHOW) == 0)
			return false;
		return (mUndoBarState & UNDO_BAR_DELETE_LAST) != 0;
	}

	private void captureAnimationDone(final int offset) {
		mHolding &= ~HOLD_CAPTURE_ANIMATION;
		if (offset == 1 && !mFilmMode) {
			// Now the capture animation is done, enable the action bar.
			mListener.onActionBarAllowed(true);
			mListener.onActionBarWanted();
		}
		snapback();
	}

	// //////////////////////////////////////////////////////////////////////////
	// Framework events
	// //////////////////////////////////////////////////////////////////////////

	// Runs in GL thread.
	private void checkFocusSwitching() {
		if (!mFilmMode)
			return;
		if (mHandler.hasMessages(MSG_SWITCH_FOCUS))
			return;
		if (switchPosition() != 0) {
			mHandler.sendEmptyMessage(MSG_SWITCH_FOCUS);
		}
	}

	// Check if the one of the conditions for hiding the undo bar has been
	// met. The conditions are:
	//
	// 1. It has been three seconds since last showing, and (a) the user has
	// touched, or (b) the deleted picture is the last remaining picture in the
	// album.
	//
	// 2. The camera is shown in full screen.
	private void checkHideUndoBar(final int addition) {
		mUndoBarState |= addition;
		if ((mUndoBarState & UNDO_BAR_SHOW) == 0)
			return;
		final boolean timeout = (mUndoBarState & UNDO_BAR_TIMEOUT) != 0;
		final boolean touched = (mUndoBarState & UNDO_BAR_TOUCHED) != 0;
		final boolean fullCamera = (mUndoBarState & UNDO_BAR_FULL_CAMERA) != 0;
		final boolean deleteLast = (mUndoBarState & UNDO_BAR_DELETE_LAST) != 0;
		if (timeout && (touched || deleteLast) || fullCamera) {
			hideUndoBar();
		}
	}

	// Draw the "no thumbnail" message
	private void drawLoadingFailIcon(final GLCanvas canvas) {
		mFallbackImage.draw(canvas, -mFallbackImageWidth / 2,
				-mFallbackImageHeight / 2);
	}

	private void drawLoadingProgress(final GLCanvas canvas) {
		mLoadingIcon.draw(canvas, -mLoadingIconWidth / 2,
				-mLoadingIconHeight / 2);
		postInvalidate(16);
	}

	// Draw a gray placeholder in the specified rectangle.
	private void drawPlaceHolder(final GLCanvas canvas, final Rect r) {
		canvas.fillRect(r.left, r.top, r.width(), r.height(), PLACEHOLDER_COLOR);
	}

	// Returns the rotation we need to do to the camera texture before drawing
	// it to the canvas, assuming the camera texture is correct when the device
	// is in its natural orientation.
	private int getCameraRotation() {
		return (mCompensation - mDisplayRotation + 360) % 360;
	}

	// Returns the alpha factor in film mode if a picture is not in the center.
	// The 0.03 lower bound is to make the item always visible a bit.
	private float getOffsetAlpha(float offset) {
		offset /= 0.5f;
		final float alpha = offset > 0 ? 1 - offset : 1 + offset;
		return Utils.clamp(alpha, 0.03f, 1f);
	}

	// Maps a scrolling progress value to the alpha factor in the fading
	// animation.
	private float getScrollAlpha(final float scrollProgress) {
		return scrollProgress < 0 ? mAlphaInterpolator
				.getInterpolation(1 - Math.abs(scrollProgress)) : 1.0f;
	}

	// Maps a scrolling progress value to the scaling factor in the fading
	// animation.
	private float getScrollScale(final float scrollProgress) {
		final float interpolatedProgress = mScaleInterpolator
				.getInterpolation(Math.abs(scrollProgress));
		final float scale = 1 - interpolatedProgress + interpolatedProgress
				* TRANSITION_SCALE_FACTOR;
		return scale;
	}

	private void hideUndoBar() {
		mHandler.removeMessages(MSG_UNDO_BAR_TIMEOUT);
		mListener.onCommitDeleteImage();
		mUndoBarState = 0;
		mUndoIndexHint = Integer.MAX_VALUE;
	}

	private void setFilmMode(final boolean enabled) {
		if (mFilmMode == enabled)
			return;
		mFilmMode = enabled;
		mPositionController.setFilmMode(mFilmMode);
		mModel.setNeedFullImage(!enabled);
		mModel.setFocusHintDirection(mFilmMode ? Model.FOCUS_HINT_PREVIOUS
				: Model.FOCUS_HINT_NEXT);
		mListener.onActionBarAllowed(!enabled);

		// Move into camera in page mode, lock
		if (!enabled && mPictures.get(0).isCamera()) {
			mListener.lockOrientation();
		}
	}

	private void setPictureSize(final int index) {
		final Picture p = mPictures.get(index);
		mPositionController.setImageSize(index, p.getSize());
	}

	// "deleteLast" means if the deletion is on the last remaining picture in
	// the album.
	private void showUndoBar(final boolean deleteLast) {
		mHandler.removeMessages(MSG_UNDO_BAR_TIMEOUT);
		mUndoBarState = UNDO_BAR_SHOW;
		if (deleteLast) {
			mUndoBarState |= UNDO_BAR_DELETE_LAST;
		}
		mHandler.sendEmptyMessageDelayed(MSG_UNDO_BAR_TIMEOUT, 3000);
	}

	private boolean slideToNextPicture() {
		if (mNextBound <= 0)
			return false;
		switchToNextImage();
		mPositionController.startHorizontalSlide();
		return true;
	}

	// //////////////////////////////////////////////////////////////////////////
	// Rendering
	// //////////////////////////////////////////////////////////////////////////

	private boolean slideToPrevPicture() {
		if (mPrevBound >= 0)
			return false;
		switchToPrevImage();
		mPositionController.startHorizontalSlide();
		return true;
	}

	// //////////////////////////////////////////////////////////////////////////
	// Film mode focus switching
	// //////////////////////////////////////////////////////////////////////////

	private void snapback() {
		if ((mHolding & ~HOLD_DELETE) != 0)
			return;
		if (!snapToNeighborImage()) {
			mPositionController.snapback();
		}
	}

	private boolean snapToNeighborImage() {
		if (mFilmMode)
			return false;

		final Rect r = mPositionController.getPosition(0);
		final int viewW = getWidth();
		final int threshold = MOVE_THRESHOLD + gapToSide(r.width(), viewW);

		// If we have moved the picture a lot, switching.
		if (viewW - r.right > threshold)
			return slideToNextPicture();
		else if (r.left > threshold)
			return slideToPrevPicture();

		return false;
	}

	private boolean swipeImages(final float velocityX, final float velocityY) {
		if (mFilmMode)
			return false;

		// Avoid swiping images if we're possibly flinging to view the
		// zoomed in picture vertically.
		final PositionController controller = mPositionController;
		final boolean isMinimal = controller.isAtMinimalScale();
		final int edges = controller.getImageAtEdges();
		if (!isMinimal && Math.abs(velocityY) > Math.abs(velocityX))
			if ((edges & PositionController.IMAGE_AT_TOP_EDGE) == 0
					|| (edges & PositionController.IMAGE_AT_BOTTOM_EDGE) == 0)
				return false;

		// If we are at the edge of the current photo and the sweeping velocity
		// exceeds the threshold, slide to the next / previous image.
		if (velocityX < -SWIPE_THRESHOLD
				&& (isMinimal || (edges & PositionController.IMAGE_AT_RIGHT_EDGE) != 0))
			return slideToNextPicture();
		else if (velocityX > SWIPE_THRESHOLD
				&& (isMinimal || (edges & PositionController.IMAGE_AT_LEFT_EDGE) != 0))
			return slideToPrevPicture();

		return false;
	}

	// Runs in main thread.
	private void switchFocus() {
		if (mHolding != 0)
			return;
		switch (switchPosition()) {
		case -1:
			switchToPrevImage();
			break;
		case 1:
			switchToNextImage();
			break;
		}
	}

	// //////////////////////////////////////////////////////////////////////////
	// Page mode focus switching
	//
	// We slide image to the next one or the previous one in two cases: 1: If
	// the user did a fling gesture with enough velocity. 2 If the user has
	// moved the picture a lot.
	// //////////////////////////////////////////////////////////////////////////

	// Returns -1 if we should switch focus to the previous picture, +1 if we
	// should switch to the next, 0 otherwise.
	private int switchPosition() {
		final Rect curr = mPositionController.getPosition(0);
		final int center = getWidth() / 2;

		if (curr.left > center && mPrevBound < 0) {
			final Rect prev = mPositionController.getPosition(-1);
			final int currDist = curr.left - center;
			final int prevDist = center - prev.right;
			if (prevDist < currDist)
				return -1;
		} else if (curr.right < center && mNextBound > 0) {
			final Rect next = mPositionController.getPosition(1);
			final int currDist = center - curr.right;
			final int nextDist = next.left - center;
			if (nextDist < currDist)
				return 1;
		}

		return 0;
	}

	private void switchToFirstImage() {
		mModel.moveTo(0);
	}

	// Switch to the previous or next picture if the hit position is inside
	// one of their boxes. This runs in main thread.
	private void switchToHitPicture(final int x, final int y) {
		if (mPrevBound < 0) {
			final Rect r = mPositionController.getPosition(-1);
			if (r.right >= x) {
				slideToPrevPicture();
				return;
			}
		}

		if (mNextBound > 0) {
			final Rect r = mPositionController.getPosition(1);
			if (r.left <= x) {
				slideToNextPicture();
				return;
			}
		}
	}

	private void switchToNextImage() {
		mModel.moveTo(mModel.getCurrentIndex() + 1);
	}

	private void switchToPrevImage() {
		mModel.moveTo(mModel.getCurrentIndex() - 1);
	}

	private boolean switchWithCaptureAnimationLocked(final int offset) {
		if (mHolding != 0)
			return true;
		if (offset == 1) {
			if (mNextBound <= 0)
				return false;
			// Temporary disable action bar until the capture animation is done.
			if (!mFilmMode) {
				mListener.onActionBarAllowed(false);
			}
			switchToNextImage();
			mPositionController.startCaptureAnimationSlide(-1);
		} else if (offset == -1) {
			if (mPrevBound >= 0)
				return false;
			if (mFilmMode) {
				setFilmMode(false);
			}

			// If we are too far away from the first image (so that we don't
			// have all the ScreenNails in-between), we go directly without
			// animation.
			if (mModel.getCurrentIndex() > SCREEN_NAIL_MAX) {
				switchToFirstImage();
				mPositionController.skipToFinalPosition();
				return true;
			}

			switchToFirstImage();
			mPositionController.startCaptureAnimationSlide(1);
		} else
			return false;
		mHolding |= HOLD_CAPTURE_ANIMATION;
		final Message m = mHandler.obtainMessage(MSG_CAPTURE_ANIMATION_DONE,
				offset, 0);
		mHandler.sendMessageDelayed(m,
				PositionController.CAPTURE_ANIMATION_TIME);
		return true;
	}

	// //////////////////////////////////////////////////////////////////////////
	// Focus switching
	// //////////////////////////////////////////////////////////////////////////

	// Update the camera rectangle due to layout change or camera relative frame
	// change.
	private void updateCameraRect() {
		// Get the width and height in framework orientation because the given
		// mCameraRelativeFrame is in that coordinates.
		int w = getWidth();
		int h = getHeight();
		if (mCompensation % 180 != 0) {
			final int tmp = w;
			w = h;
			h = tmp;
		}
		final int l = mCameraRelativeFrame.left;
		final int t = mCameraRelativeFrame.top;
		final int r = mCameraRelativeFrame.right;
		final int b = mCameraRelativeFrame.bottom;

		// Now convert it to the coordinates we are using.
		switch (mCompensation) {
		case 0:
			mCameraRect.set(l, t, r, b);
			break;
		case 90:
			mCameraRect.set(h - b, l, h - t, r);
			break;
		case 180:
			mCameraRect.set(w - r, h - b, w - l, h - t);
			break;
		case 270:
			mCameraRect.set(t, w - r, b, w - l);
			break;
		}

		Log.d(TAG, "compensation = " + mCompensation
				+ ", CameraRelativeFrame = " + mCameraRelativeFrame
				+ ", mCameraRect = " + mCameraRect);
	}

	void post(final Runnable r, final long delayMillis) {
		mHandler.postDelayed(r, delayMillis);
	}

	void postInvalidate(final long delayMillis) {
		mHandler.postDelayed(new InvalidateRunnable(this), delayMillis);
	}

	// Returns the scrolling progress value for an object moving out of a
	// view. The progress value measures how much the object has moving out of
	// the view. The object currently displays in [left, right), and the view is
	// at [0, viewWidth].
	//
	// The returned value is negative when the object is moving right, and
	// positive when the object is moving left. The value goes to -1 or 1 when
	// the object just moves out of the view completely. The value is 0 if the
	// object currently fills the view.
	private static float calculateMoveOutProgress(final int left,
			final int right, final int viewWidth) {
		// w = object width
		// viewWidth = view width
		final int w = right - left;

		// If the object width is smaller than the view width,
		// |....view....|
		// |<-->| progress = -1 when left = viewWidth
		// |<-->| progress = 0 when left = viewWidth / 2 - w / 2
		// |<-->| progress = 1 when left = -w
		if (w < viewWidth) {
			final int zx = viewWidth / 2 - w / 2;
			if (left > zx)
				return -(left - zx) / (float) (viewWidth - zx); // progress =
																// (0, -1]
			else
				return (left - zx) / (float) (-w - zx); // progress = [0, 1]
		}

		// If the object width is larger than the view width,
		// |..view..|
		// |<--------->| progress = -1 when left = viewWidth
		// |<--------->| progress = 0 between left = 0
		// |<--------->| and right = viewWidth
		// |<--------->| progress = 1 when right = 0
		if (left > 0)
			return -left / (float) viewWidth;

		if (right < viewWidth)
			return (viewWidth - right) / (float) viewWidth;

		return 0;
	}

	// //////////////////////////////////////////////////////////////////////////
	// Opening Animation
	// //////////////////////////////////////////////////////////////////////////

	private static int gapToSide(final int imageWidth, final int viewWidth) {
		return Math.max(0, (viewWidth - imageWidth) / 2);
	}

	// //////////////////////////////////////////////////////////////////////////
	// Capture Animation
	// //////////////////////////////////////////////////////////////////////////

	private static int getRotated(final int degree, final int original,
			final int theother) {
		return degree % 180 == 0 ? original : theother;
	}

	// Returns an interpolated value for the page/film transition.
	// When ratio = 0, the result is from.
	// When ratio = 1, the result is to.
	private static float interpolate(final float ratio, final float from,
			final float to) {
		return from + (to - from) * ratio * ratio;
	}

	public interface Listener {
		public void lockOrientation();

		public void onActionBarAllowed(boolean allowed);

		public void onActionBarWanted();

		public void onCommitDeleteImage();

		public void onCurrentImageUpdated();

		public void onDeleteImage(Path path, int offset);

		public void onFullScreenChanged(boolean full);

		public void onSingleTapUp(int x, int y);

		public void onUndoDeleteImage();

		public void unlockOrientation();
	}

	// //////////////////////////////////////////////////////////////////////////
	// Card deck effect calculation
	// //////////////////////////////////////////////////////////////////////////

	public interface Model extends TileImageView.Model {
		public static final int LOADING_INIT = 0;
		public static final int LOADING_COMPLETE = 1;

		public static final int LOADING_FAIL = 2;

		// When data change happens, we need to decide which MediaItem to focus
		// on.
		//
		// 1. If focus hint path != null, we try to focus on it if we can find
		// it. This is used for undo a deletion, so we can focus on the
		// undeleted item.
		//
		// 2. Otherwise try to focus on the MediaItem that is currently focused,
		// if we can find it.
		//
		// 3. Otherwise try to focus on the previous MediaItem or the next
		// MediaItem, depending on the value of focus hint direction.
		public static final int FOCUS_HINT_NEXT = 0;

		public static final int FOCUS_HINT_PREVIOUS = 1;

		public int getCurrentIndex();

		// Returns the rotation for the specified picture.
		public int getImageRotation(int offset);

		// Returns the size for the specified picture. If the size information
		// is
		// not avaiable, width = height = 0.
		public void getImageSize(int offset, Size size);

		public int getLoadingState(int offset);

		// Returns the media item for the specified picture.
		public MediaItem getMediaItem(int offset);

		// This amends the getScreenNail() method of TileImageView.Model to get
		// ScreenNail at previous (negative offset) or next (positive offset)
		// positions. Returns null if the specified ScreenNail is unavailable.
		public ScreenNail getScreenNail(int offset);

		// Returns true if the item is the Camera preview.
		public boolean isCamera(int offset);

		public boolean isLoadComplete(int offset);

		public boolean isLoadFailed(int offset);

		public void moveTo(int index);

		public void setFocusHintDirection(int direction);

		public void setFocusHintPath(Path path);

		// Set this to true if we need the model to provide full images.
		public void setNeedFullImage(boolean enabled);
	}

	public static class Size {
		public int width;
		public int height;
	}

	private class MyGestureListener implements GestureRecognizer.Listener {
		private boolean mIgnoreUpEvent = false;
		// If we can change mode for this scale gesture.
		private boolean mCanChangeMode;
		// If we have changed the film mode in this scaling gesture.
		private boolean mModeChanged;
		// If this scaling gesture should be ignored.
		private boolean mIgnoreScalingGesture;
		// whether the down action happened while the view is scrolling.
		private boolean mDownInScrolling;
		// If we should ignore all gestures other than onSingleTapUp.
		private boolean mIgnoreSwipingGesture;
		// If a scrolling has happened after a down gesture.
		private boolean mScrolledAfterDown;
		// If the first scrolling move is in X direction. In the film mode, X
		// direction scrolling is normal scrolling. but Y direction scrolling is
		// a delete gesture.
		private boolean mFirstScrollX;
		// The accumulated Y delta that has been sent to mPositionController.
		private int mDeltaY;
		// The accumulated scaling change from a scaling gesture.
		private float mAccScale;

		@Override
		public boolean onDoubleTap(final float x, final float y) {
			if (mIgnoreSwipingGesture)
				return true;
			if (mPictures.get(0).isCamera())
				return false;
			final PositionController controller = mPositionController;
			final float scale = controller.getImageScale();
			// onDoubleTap happened on the second ACTION_DOWN.
			// We need to ignore the next UP event.
			mIgnoreUpEvent = true;
			if (scale <= 1.0f || controller.isAtMinimalScale()) {
				controller.zoomIn(x, y, Math.max(1.5f, scale * 1.5f));
			} else {
				controller.resetToFullView();
			}
			return true;
		}

		@Override
		public void onDown(final float x, final float y) {
			checkHideUndoBar(UNDO_BAR_TOUCHED);

			mDeltaY = 0;
			mModeChanged = false;

			if (mIgnoreSwipingGesture)
				return;

			mHolding |= HOLD_TOUCH_DOWN;

			if (mFilmMode && mPositionController.isScrolling()) {
				mDownInScrolling = true;
				mPositionController.stopScrolling();
			} else {
				mDownInScrolling = false;
			}

			mScrolledAfterDown = false;
			if (mFilmMode) {
				final int xi = (int) (x + 0.5f);
				final int yi = (int) (y + 0.5f);
				mTouchBoxIndex = mPositionController.hitTest(xi, yi);
				if (mTouchBoxIndex < mPrevBound || mTouchBoxIndex > mNextBound) {
					mTouchBoxIndex = Integer.MAX_VALUE;
				} else {
					mTouchBoxDeletable = false;
				}
			} else {
				mTouchBoxIndex = Integer.MAX_VALUE;
			}
		}

		@Override
		public boolean onFling(final float velocityX, final float velocityY) {
			if (mIgnoreSwipingGesture)
				return true;
			if (mModeChanged)
				return true;
			if (swipeImages(velocityX, velocityY)) {
				mIgnoreUpEvent = true;
			} else {
				flingImages(velocityX, velocityY);
			}
			return true;
		}

		@Override
		public boolean onScale(final float focusX, final float focusY,
				final float scale) {
			if (mIgnoreSwipingGesture)
				return true;
			if (mIgnoreScalingGesture)
				return true;
			if (mModeChanged)
				return true;
			if (Float.isNaN(scale) || Float.isInfinite(scale))
				return false;

			final int outOfRange = mPositionController.scaleBy(scale, focusX,
					focusY);

			// We wait for a large enough scale change before changing mode.
			// Otherwise we may mistakenly treat a zoom-in gesture as zoom-out
			// or vice versa.
			mAccScale *= scale;
			final boolean largeEnough = mAccScale < 0.97f || mAccScale > 1.03f;

			// If mode changes, we treat this scaling gesture has ended.
			if (mCanChangeMode && largeEnough) {
				if (outOfRange < 0 && !mFilmMode || outOfRange > 0 && mFilmMode) {
					stopExtraScalingIfNeeded();

					// Removing the touch down flag allows snapback to happen
					// for film mode change.
					mHolding &= ~HOLD_TOUCH_DOWN;
					setFilmMode(!mFilmMode);

					// We need to call onScaleEnd() before setting mModeChanged
					// to true.
					onScaleEnd();
					mModeChanged = true;
					return true;
				}
			}

			if (outOfRange != 0) {
				startExtraScalingIfNeeded();
			} else {
				stopExtraScalingIfNeeded();
			}
			return true;
		}

		@Override
		public boolean onScaleBegin(final float focusX, final float focusY) {
			if (mIgnoreSwipingGesture)
				return true;
			// We ignore the scaling gesture if it is a camera preview.
			mIgnoreScalingGesture = mPictures.get(0).isCamera();
			if (mIgnoreScalingGesture)
				return true;
			mPositionController.beginScale(focusX, focusY);
			// We can change mode if we are in film mode, or we are in page
			// mode and at minimal scale.
			mCanChangeMode = mFilmMode
					|| mPositionController.isAtMinimalScale();
			mAccScale = 1f;
			return true;
		}

		@Override
		public void onScaleEnd() {
			if (mIgnoreSwipingGesture)
				return;
			if (mIgnoreScalingGesture)
				return;
			if (mModeChanged)
				return;
			mPositionController.endScale();
		}

		@Override
		public boolean onScroll(final float dx, final float dy,
				final float totalX, final float totalY) {
			if (mIgnoreSwipingGesture)
				return true;
			if (!mScrolledAfterDown) {
				mScrolledAfterDown = true;
				mFirstScrollX = Math.abs(dx) > Math.abs(dy);
			}

			final int dxi = (int) (-dx + 0.5f);
			final int dyi = (int) (-dy + 0.5f);
			if (mFilmMode) {
				if (mFirstScrollX) {
					mPositionController.scrollFilmX(dxi);
				} else {
					if (mTouchBoxIndex == Integer.MAX_VALUE)
						return true;
					final int newDeltaY = calculateDeltaY(totalY);
					final int d = newDeltaY - mDeltaY;
					if (d != 0) {
						mPositionController.scrollFilmY(mTouchBoxIndex, d);
						mDeltaY = newDeltaY;
					}
				}
			} else {
				mPositionController.scrollPage(dxi, dyi);
			}
			return true;
		}

		@Override
		public boolean onSingleTapUp(final float x, final float y) {
			// We do this in addition to onUp() because we want the snapback of
			// setFilmMode to happen.
			mHolding &= ~HOLD_TOUCH_DOWN;

			if (mFilmMode && !mDownInScrolling) {
				switchToHitPicture((int) (x + 0.5f), (int) (y + 0.5f));
				setFilmMode(false);
				mIgnoreUpEvent = true;
				return true;
			}

			if (mListener != null) {
				// Do the inverse transform of the touch coordinates.
				final Matrix m = getGLRoot().getCompensationMatrix();
				final Matrix inv = new Matrix();
				m.invert(inv);
				final float[] pts = new float[] { x, y };
				inv.mapPoints(pts);
				mListener.onSingleTapUp((int) (pts[0] + 0.5f),
						(int) (pts[1] + 0.5f));
			}
			return true;
		}

		@Override
		public void onUp() {
			if (mIgnoreSwipingGesture)
				return;

			mHolding &= ~HOLD_TOUCH_DOWN;
			mEdgeView.onRelease();

			// If we scrolled in Y direction far enough, treat it as a delete
			// gesture.
			if (mFilmMode && mScrolledAfterDown && !mFirstScrollX
					&& mTouchBoxIndex != Integer.MAX_VALUE) {
				final Rect r = mPositionController.getPosition(mTouchBoxIndex);
				final int h = getHeight();
				if (Math.abs(r.centerY() - h * 0.5f) > 0.4f * h) {
					final int duration = mPositionController.flingFilmY(
							mTouchBoxIndex, 0);
					if (duration >= 0) {
						mPositionController
								.setPopFromTop(r.centerY() < h * 0.5f);
						deleteAfterAnimation(duration);
					}
				}
			}

			if (mIgnoreUpEvent) {
				mIgnoreUpEvent = false;
				return;
			}

			snapback();
		}

		public void setSwipingEnabled(final boolean enabled) {
			mIgnoreSwipingGesture = !enabled;
		}

		private int calculateDeltaY(float delta) {
			if (mTouchBoxDeletable)
				return (int) (delta + 0.5f);

			// don't let items that can't be deleted be dragged more than
			// maxScrollDistance, and make it harder and harder to drag.
			final int size = getHeight();
			final float maxScrollDistance = 0.15f * size;
			if (Math.abs(delta) >= size) {
				delta = delta > 0 ? maxScrollDistance : -maxScrollDistance;
			} else {
				delta = maxScrollDistance
						* FloatMath.sin(delta / size * (float) (Math.PI / 2));
			}
			return (int) (delta + 0.5f);
		}

		private void deleteAfterAnimation(final int duration) {
			final MediaItem item = mModel.getMediaItem(mTouchBoxIndex);
			if (item == null)
				return;
			mListener.onCommitDeleteImage();
			mUndoIndexHint = mModel.getCurrentIndex() + mTouchBoxIndex;
			mHolding |= HOLD_DELETE;
			final Message m = mHandler.obtainMessage(MSG_DELETE_ANIMATION_DONE);
			m.obj = item.getPath();
			m.arg1 = mTouchBoxIndex;
			mHandler.sendMessageDelayed(m, duration);
		}

		private boolean flingImages(final float velocityX, final float velocityY) {
			final int vx = (int) (velocityX + 0.5f);
			int vy = (int) (velocityY + 0.5f);
			if (!mFilmMode)
				return mPositionController.flingPage(vx, vy);
			if (Math.abs(velocityX) > Math.abs(velocityY))
				return mPositionController.flingFilmX(vx);
			// If we scrolled in Y direction fast enough, treat it as a delete
			// gesture.
			if (!mFilmMode || mTouchBoxIndex == Integer.MAX_VALUE
					|| !mTouchBoxDeletable)
				return false;
			final int maxVelocity = GalleryUtils
					.dpToPixel(MAX_DISMISS_VELOCITY);
			final int escapeVelocity = GalleryUtils
					.dpToPixel(SWIPE_ESCAPE_VELOCITY);
			final int centerY = mPositionController.getPosition(mTouchBoxIndex)
					.centerY();
			final boolean fastEnough = Math.abs(vy) > escapeVelocity
					&& Math.abs(vy) > Math.abs(vx)
					&& vy > 0 == centerY > getHeight() / 2;
			if (fastEnough) {
				vy = Math.min(vy, maxVelocity);
				final int duration = mPositionController.flingFilmY(
						mTouchBoxIndex, vy);
				if (duration >= 0) {
					mPositionController.setPopFromTop(vy < 0);
					deleteAfterAnimation(duration);
					// We reset mTouchBoxIndex, so up() won't check if Y
					// scrolled far enough to be a delete gesture.
					mTouchBoxIndex = Integer.MAX_VALUE;
					return true;
				}
			}
			return false;
		}

		private void startExtraScalingIfNeeded() {
			if (!mCancelExtraScalingPending) {
				mHandler.sendEmptyMessageDelayed(MSG_CANCEL_EXTRA_SCALING, 700);
				mPositionController.setExtraScalingRange(true);
				mCancelExtraScalingPending = true;
			}
		}

		private void stopExtraScalingIfNeeded() {
			if (mCancelExtraScalingPending) {
				mHandler.removeMessages(MSG_CANCEL_EXTRA_SCALING);
				mPositionController.setExtraScalingRange(false);
				mCancelExtraScalingPending = false;
			}
		}
	}

	private interface Picture {
		void draw(GLCanvas canvas, Rect r);

		void forceSize(); // called when mCompensation changes

		int getLoadingState();

		Size getSize();

		boolean isCamera(); // whether the picture is a camera preview

		void reload();

		void setScreenNail(ScreenNail s);
	}

	private class ScreenNailPicture implements Picture {
		private final int mIndex;
		private int mRotation;
		private ScreenNail mScreenNail;
		private boolean mIsCamera;
		private int mLoadingState = Model.LOADING_INIT;
		private final Size mSize = new Size();

		public ScreenNailPicture(final int index) {
			mIndex = index;
		}

		@Override
		public void draw(final GLCanvas canvas, final Rect r) {
			if (mScreenNail == null) {
				// Draw a placeholder rectange if there should be a picture in
				// this position (but somehow there isn't).
				if (mIndex >= mPrevBound && mIndex <= mNextBound) {
					drawPlaceHolder(canvas, r);
				}
				return;
			}
			final int w = getWidth();
			final int h = getHeight();
			if (r.left >= w || r.right <= 0 || r.top >= h || r.bottom <= 0) {
				mScreenNail.noDraw();
				return;
			}

			final float filmRatio = mPositionController.getFilmRatio();
			final boolean wantsCardEffect = CARD_EFFECT && mIndex > 0
					&& filmRatio != 1f && !mPictures.get(0).isCamera();
			final boolean wantsOffsetEffect = OFFSET_EFFECT && false;
			final int cx = wantsCardEffect ? (int) (interpolate(filmRatio,
					w / 2, r.centerX()) + 0.5f) : r.centerX();
			final int cy = r.centerY();
			canvas.save(GLCanvas.SAVE_FLAG_MATRIX | GLCanvas.SAVE_FLAG_ALPHA);
			canvas.translate(cx, cy);
			if (wantsCardEffect) {
				float progress = (float) (w / 2 - r.centerX()) / w;
				progress = Utils.clamp(progress, -1, 1);
				float alpha = getScrollAlpha(progress);
				float scale = getScrollScale(progress);
				alpha = interpolate(filmRatio, alpha, 1f);
				scale = interpolate(filmRatio, scale, 1f);
				canvas.multiplyAlpha(alpha);
				canvas.scale(scale, scale, 1);
			} else if (wantsOffsetEffect) {
				final float offset = (float) (r.centerY() - h / 2) / h;
				final float alpha = getOffsetAlpha(offset);
				canvas.multiplyAlpha(alpha);
			}
			if (mRotation != 0) {
				canvas.rotate(mRotation, 0, 0, 1);
			}
			final int drawW = getRotated(mRotation, r.width(), r.height());
			final int drawH = getRotated(mRotation, r.height(), r.width());
			mScreenNail.draw(canvas, -drawW / 2, -drawH / 2, drawW, drawH);
			if (isScreenNailAnimating()) {
				invalidate();
			}
			if (mModel.isLoadFailed(mIndex)) {
				drawLoadingFailIcon(canvas);
			} else if (!mModel.isLoadComplete(mIndex)) {
				drawLoadingProgress(canvas);
			}
			canvas.restore();
		}

		@Override
		public void forceSize() {
			updateSize();
			mPositionController.forceImageSize(mIndex, mSize);
		}

		@Override
		public int getLoadingState() {
			return mLoadingState;
		}

		@Override
		public Size getSize() {
			return mSize;
		}

		@Override
		public boolean isCamera() {
			return mIsCamera;
		}

		@Override
		public void reload() {
			mIsCamera = mModel.isCamera(mIndex);
			mLoadingState = mModel.getLoadingState(mIndex);
			setScreenNail(mModel.getScreenNail(mIndex));
			updateSize();
		}

		@Override
		public void setScreenNail(final ScreenNail s) {
			mScreenNail = s;
		}

		private boolean isScreenNailAnimating() {
			return mScreenNail instanceof BitmapScreenNail
					&& ((BitmapScreenNail) mScreenNail).isAnimating();
		}

		private void updateSize() {
			if (mIsCamera) {
				mRotation = getCameraRotation();
			} else {
				mRotation = mModel.getImageRotation(mIndex);
			}

			if (mScreenNail != null) {
				mSize.width = mScreenNail.getWidth();
				mSize.height = mScreenNail.getHeight();
			} else {
				// If we don't have ScreenNail available, we can still try to
				// get the size information of it.
				mModel.getImageSize(mIndex, mSize);
			}

			final int w = mSize.width;
			final int h = mSize.height;
			mSize.width = getRotated(mRotation, w, h);
			mSize.height = getRotated(mRotation, h, w);
		}
	}

	// This interpolator emulates the rate at which the perceived scale of an
	// object changes as its distance from a camera increases. When this
	// interpolator is applied to a scale animation on a view, it evokes the
	// sense that the object is shrinking due to moving away from the camera.
	private static class ZInterpolator {
		private final float focalLength;

		public ZInterpolator(final float foc) {
			focalLength = foc;
		}

		public float getInterpolation(final float input) {
			return (1.0f - focalLength / (focalLength + input))
					/ (1.0f - focalLength / (focalLength + 1.0f));
		}
	}

	// //////////////////////////////////////////////////////////////////////////
	// Simple public utilities
	// //////////////////////////////////////////////////////////////////////////

	class FullPicture implements Picture {
		private int mRotation;
		private boolean mIsCamera;
		private int mLoadingState = Model.LOADING_INIT;
		private final Size mSize = new Size();
		private boolean mWasCameraCenter;

		@Override
		public void draw(final GLCanvas canvas, final Rect r) {
			drawTileView(canvas, r);

			// We want to have the following transitions:
			// (1) Move camera preview out of its place: switch to film mode
			// (2) Move camera preview into its place: switch to page mode
			// The extra mWasCenter check makes sure (1) does not apply if in
			// page mode, we move _to_ the camera preview from another picture.

			// Holdings except touch-down prevent the transitions.
			if ((mHolding & ~HOLD_TOUCH_DOWN) != 0)
				return;

			final boolean isCenter = mPositionController.isCenter();
			final boolean isCameraCenter = mIsCamera && isCenter
					&& !canUndoLastPicture();

			if (mWasCameraCenter && mIsCamera && !isCenter && !mFilmMode) {
				// Temporary disabled to de-emphasize filmstrip.
				// setFilmMode(true);
			} else if (!mWasCameraCenter && isCameraCenter && mFilmMode) {
				setFilmMode(false);
			}

			if (isCameraCenter && !mFilmMode) {
				// Move into camera in page mode, lock
				mListener.lockOrientation();
			}

			mWasCameraCenter = isCameraCenter;
		}

		@Override
		public void forceSize() {
			updateSize();
			mPositionController.forceImageSize(0, mSize);
		}

		@Override
		public int getLoadingState() {
			return mLoadingState;
		}

		// public void FullPicture(final TileImageView tileView) {
		// mTileView = tileView;
		// }

		@Override
		public Size getSize() {
			return mSize;
		}

		@Override
		public boolean isCamera() {
			return mIsCamera;
		}

		@Override
		public void reload() {
			// mImageWidth and mImageHeight will get updated
			mTileView.notifyModelInvalidated();

			mIsCamera = mModel.isCamera(0);
			mLoadingState = mModel.getLoadingState(0);
			setScreenNail(mModel.getScreenNail(0));
			updateSize();
		}

		@Override
		public void setScreenNail(final ScreenNail s) {
			mTileView.setScreenNail(s);
		}

		private void drawTileView(final GLCanvas canvas, final Rect r) {
			float imageScale = mPositionController.getImageScale();
			final int viewW = getWidth();
			final int viewH = getHeight();
			float cx = r.exactCenterX();
			final float cy = r.exactCenterY();
			float scale = 1f; // the scaling factor due to card effect

			canvas.save(GLCanvas.SAVE_FLAG_MATRIX | GLCanvas.SAVE_FLAG_ALPHA);
			final float filmRatio = mPositionController.getFilmRatio();
			final boolean wantsCardEffect = CARD_EFFECT && !mIsCamera
					&& filmRatio != 1f && !mPictures.get(-1).isCamera()
					&& !mPositionController.inOpeningAnimation();
			final boolean wantsOffsetEffect = OFFSET_EFFECT && false;
			if (wantsCardEffect) {
				// Calculate the move-out progress value.
				final int left = r.left;
				final int right = r.right;
				float progress = calculateMoveOutProgress(left, right, viewW);
				progress = Utils.clamp(progress, -1f, 1f);

				// We only want to apply the fading animation if the scrolling
				// movement is to the right.
				if (progress < 0) {
					scale = getScrollScale(progress);
					float alpha = getScrollAlpha(progress);
					scale = interpolate(filmRatio, scale, 1f);
					alpha = interpolate(filmRatio, alpha, 1f);

					imageScale *= scale;
					canvas.multiplyAlpha(alpha);

					float cxPage; // the cx value in page mode
					if (right - left <= viewW) {
						// If the picture is narrower than the view, keep it at
						// the center of the view.
						cxPage = viewW / 2f;
					} else {
						// If the picture is wider than the view (it's
						// zoomed-in), keep the left edge of the object align
						// the the left edge of the view.
						cxPage = (right - left) * scale / 2f;
					}
					cx = interpolate(filmRatio, cxPage, cx);
				}
			} else if (wantsOffsetEffect) {
				final float offset = (float) (r.centerY() - viewH / 2) / viewH;
				final float alpha = getOffsetAlpha(offset);
				canvas.multiplyAlpha(alpha);
			}

			// Draw the tile view.
			setTileViewPosition(cx, cy, viewW, viewH, imageScale);
			renderChild(canvas, mTileView);

			// Draw the play video icon and the message.
			canvas.translate((int) (cx + 0.5f), (int) (cy + 0.5f));
			if (mModel.isLoadFailed(0)) {
				drawLoadingFailIcon(canvas);
			} else if (!mModel.isLoadComplete(0)) {
				drawLoadingProgress(canvas);
			}

			// Draw a debug indicator showing which picture has focus (index ==
			// 0).
			// canvas.fillRect(-10, -10, 20, 20, 0x80FF00FF);

			canvas.restore();
		}

		// Set the position of the tile view
		private void setTileViewPosition(final float cx, final float cy,
				final int viewW, final int viewH, final float scale) {
			// Find out the bitmap coordinates of the center of the view
			final int imageW = mPositionController.getImageWidth();
			final int imageH = mPositionController.getImageHeight();
			final int centerX = (int) (imageW / 2f + (viewW / 2f - cx) / scale + 0.5f);
			final int centerY = (int) (imageH / 2f + (viewH / 2f - cy) / scale + 0.5f);

			final int inverseX = imageW - centerX;
			final int inverseY = imageH - centerY;
			int x, y;
			switch (mRotation) {
			case 0:
				x = centerX;
				y = centerY;
				break;
			case 90:
				x = centerY;
				y = inverseX;
				break;
			case 180:
				x = inverseX;
				y = inverseY;
				break;
			case 270:
				x = inverseY;
				y = centerX;
				break;
			default:
				throw new RuntimeException(String.valueOf(mRotation));
			}
			mTileView.setPosition(x, y, scale, mRotation);
		}

		private void updateSize() {
			if (mIsCamera) {
				mRotation = getCameraRotation();
			} else {
				mRotation = mModel.getImageRotation(0);
			}

			final int w = mTileView.mImageWidth;
			final int h = mTileView.mImageHeight;
			mSize.width = getRotated(mRotation, w, h);
			mSize.height = getRotated(mRotation, h, w);
		}
	}

	static final class InvalidateRunnable implements Runnable {
		private final PhotoView view;

		InvalidateRunnable(final PhotoView view) {
			this.view = view;
		}

		@Override
		public void run() {
			view.invalidate();
		}

	}

	class MyHandler extends SynchronizedHandler {
		public MyHandler(final GLRoot root) {
			super(root);
		}

		@Override
		public void handleMessage(final Message message) {
			switch (message.what) {
			case MSG_CANCEL_EXTRA_SCALING: {
				mGestureRecognizer.cancelScale();
				mPositionController.setExtraScalingRange(false);
				mCancelExtraScalingPending = false;
				break;
			}
			case MSG_SWITCH_FOCUS: {
				switchFocus();
				break;
			}
			case MSG_CAPTURE_ANIMATION_DONE: {
				// message.arg1 is the offset parameter passed to
				// switchWithCaptureAnimation().
				captureAnimationDone(message.arg1);
				break;
			}
			case MSG_DELETE_ANIMATION_DONE: {
				// message.obj is the Path of the MediaItem which should be
				// deleted. message.arg1 is the offset of the image.
				mListener.onDeleteImage((Path) message.obj, message.arg1);
				// Normally a box which finishes delete animation will hold
				// position until the underlying MediaItem is actually
				// deleted, and HOLD_DELETE will be cancelled that time. In
				// case the MediaItem didn't actually get deleted in 2
				// seconds, we will cancel HOLD_DELETE and make it bounce
				// back.

				// We make sure there is at most one MSG_DELETE_DONE
				// in the handler.
				mHandler.removeMessages(MSG_DELETE_DONE);
				final Message m = mHandler.obtainMessage(MSG_DELETE_DONE);
				mHandler.sendMessageDelayed(m, 2000);

				int numberOfPictures = mNextBound - mPrevBound + 1;
				if (numberOfPictures == 2) {
					if (mModel.isCamera(mNextBound)
							|| mModel.isCamera(mPrevBound)) {
						numberOfPictures--;
					}
				}
				showUndoBar(numberOfPictures <= 1);
				break;
			}
			case MSG_DELETE_DONE: {
				if (!mHandler.hasMessages(MSG_DELETE_ANIMATION_DONE)) {
					mHolding &= ~HOLD_DELETE;
					snapback();
				}
				break;
			}
			case MSG_UNDO_BAR_TIMEOUT: {
				checkHideUndoBar(UNDO_BAR_TIMEOUT);
				break;
			}
			case MSG_UNDO_BAR_FULL_CAMERA: {
				checkHideUndoBar(UNDO_BAR_FULL_CAMERA);
				break;
			}
			default:
				throw new AssertionError(message.what);
			}
		}
	}
}
