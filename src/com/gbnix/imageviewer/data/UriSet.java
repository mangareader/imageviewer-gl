package com.gbnix.imageviewer.data;

import java.util.ArrayList;

import android.net.Uri;

import com.gbnix.imageviewer.app.GalleryApp;

public class UriSet extends MediaSet {

	private final Uri[] mUris;
	private final DataManager mDataManager;

	public UriSet(final GalleryApp context, final Path path, final Uri[] uris) {
		super(path, nextVersionNumber());
		mDataManager = context.getDataManager();
		mUris = uris;
	}

	@Override
	public ArrayList<MediaItem> getMediaItem(final int start, final int count) {
		final ArrayList<MediaItem> temp = new ArrayList<MediaItem>();
		final int len = Math.min(start + count, mUris.length);
		for (int i = start; i < len; i++) {
			final Path path = mDataManager.findPathByUri(mUris[i], "image/*");
			temp.add((MediaItem) mDataManager.getMediaObject(path));
		}
		return temp;
	}

	@Override
	public int getMediaItemCount() {
		return mUris.length;
	}

	@Override
	public String getName() {
		return "Uris";
	}

	@Override
	public long reload() {
		return mDataVersion;
	}

}
