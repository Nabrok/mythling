/**
 * Copyright 2015 Donald Oakes
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
package com.oakesville.mythling;

import java.io.IOException;
import java.text.ParseException;

import org.json.JSONException;

import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v13.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import com.oakesville.mythling.app.AppData;
import com.oakesville.mythling.firetv.FireTvViewPager;
import com.oakesville.mythling.firetv.FireTvViewPager.DpadMediaKeyHandler;
import com.oakesville.mythling.media.MediaList;
import com.oakesville.mythling.media.MediaSettings.MediaType;
import com.oakesville.mythling.media.MediaSettings.SortType;
import com.oakesville.mythling.util.Reporter;

/**
 * Activity for side-scrolling through paged detail views of MythTV media w/artwork.
 */
public class MediaPagerActivity extends MediaActivity {
    private static final String TAG = MediaPagerActivity.class.getSimpleName();

    private ViewPager pager;
    private MediaPagerAdapter pagerAdapter;

    private SeekBar positionBar;

    public String getCharSet() {
        return mediaList.getCharSet();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getAppSettings().isFireTv())
            setContentView(R.layout.firetv_pager);
        else
            setContentView(R.layout.pager);

        createProgressBar();

        setPathFromIntent();

        pager = (ViewPager) findViewById(R.id.pager);

        modeSwitch = getIntent().getBooleanExtra(MODE_SWITCH, false);
        setSelItemIndex(getIntent().getIntExtra(SEL_ITEM_INDEX, 0));

        getActionBar().setDisplayHomeAsUpEnabled(!getPath().isEmpty());
    }


    @Override
    protected void onResume() {
        try {
            if (getAppData() == null || getAppData().isExpired())
                refresh();
            else
                populate();
        } catch (Exception ex) {
            if (BuildConfig.DEBUG)
                Log.e(TAG, ex.getMessage(), ex);
            if (getAppSettings().isErrorReportingEnabled())
                new Reporter(ex).send();
            Toast.makeText(getApplicationContext(), getString(R.string.error_) + ex.toString(), Toast.LENGTH_LONG).show();
        }

        super.onResume();
    }

    public void populate() throws IOException, JSONException, ParseException {
        if (getAppData() == null) {
            startProgress();
            AppData appData = new AppData(getApplicationContext());
            appData.readMediaList(getMediaType());
            setAppData(appData);
            stopProgress();
        } else if (getMediaType() != null && getMediaType() != getAppData().getMediaList().getMediaType()) {
            // media type was changed, then back button was pressed
            getAppSettings().setMediaType(getMediaType());
            refresh();
            return;
        }
        mediaList = getAppData().getMediaList();
        storageGroups = getAppData().getStorageGroups();
        setMediaType(mediaList.getMediaType());

        pagerAdapter = new MediaPagerAdapter(getFragmentManager());
        pager.setAdapter(pagerAdapter);
        pager.setOnPageChangeListener(new OnPageChangeListener() {
            public void onPageSelected(int position) {
                setSelItemIndex(position);
                positionBar.setProgress(getSelItemIndex() + 1);
                TextView curItemView = (TextView) findViewById(R.id.currentItem);
                curItemView.setText(String.valueOf(getSelItemIndex() + 1));
            }
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            }
            public void onPageScrollStateChanged(int state) {
            }
        });

        positionBar = (SeekBar) findViewById(R.id.pagerPosition);
        positionBar.setMax(getListables().size());
        positionBar.setProgress(1);
        positionBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser)
                    setSelItemIndex(progress);
            }
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            public void onStopTrackingTouch(SeekBar seekBar) {
                pager.setCurrentItem(getSelItemIndex());
            }
        });

        if (getAppSettings().isFireTv()) {
            ((FireTvViewPager)pager).setDpadMediaKeyHandler(new DpadMediaKeyHandler() {
                public boolean handleFastForward() {
                    pager.setCurrentItem(getListables().size() - 1);
                    positionBar.setProgress(getListables().size());
                    return true;
                }
                public boolean handleRewind() {
                    pager.setCurrentItem(0);
                    positionBar.setProgress(1);
                    return true;
                }
            });
        }

        ImageButton button = (ImageButton) findViewById(R.id.gotoFirst);
        button.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                pager.setCurrentItem(0);
                positionBar.setProgress(1);
            }
        });
        button = (ImageButton) findViewById(R.id.gotoLast);
        button.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                pager.setCurrentItem(getListables().size() - 1);
                positionBar.setProgress(getListables().size());
            }
        });

        TextView tv = (TextView) findViewById(R.id.lastItem);
        tv.setText(String.valueOf(getListables().size()));

        updateActionMenu();

        if (getListables().size() <= getSelItemIndex())
            setSelItemIndex(0);

        pager.setCurrentItem(getSelItemIndex());
        positionBar.setProgress(getSelItemIndex());
        TextView curItemView = (TextView) findViewById(R.id.currentItem);
        if (curItemView != null)
            curItemView.setText(String.valueOf(getSelItemIndex() + 1));
    }

    public void refresh() {
        super.refresh();
        setPath("");
        mediaList = new MediaList();

        startProgress();
        getAppSettings().validate();

        refreshMediaList();
    }

    protected void goListView() {
        goListView("list");
    }

    protected void goSplitView() {
        goListView("split");
    }

    protected void goListView(String mode) {
        if (mediaList.getMediaType() == MediaType.recordings && getAppSettings().getMediaSettings().getSortType() == SortType.byTitle)
            getAppSettings().clearCache(); // refresh since we're switching from flattened hierarchy

        if (getPath() == null || getPath().isEmpty()) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(MODE_SWITCH, mode);
            intent.putExtra(SEL_ITEM_INDEX, getSelItemIndex());
            startActivity(intent);
        } else {
            Uri uri = new Uri.Builder().path(getPath()).build();
            Intent intent = new Intent(Intent.ACTION_VIEW, uri, getApplicationContext(), MediaListActivity.class);
            intent.putExtra(MODE_SWITCH, mode);
            intent.putExtra(SEL_ITEM_INDEX, getSelItemIndex());
            startActivity(intent);
        }
    }

    public ListView getListView() {
        return null;
    }

    @Override
    public void onBackPressed() {
        if (modeSwitch) {
            modeSwitch = false;
            Intent intent = new Intent(this, MediaPagerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        } else {
            super.onBackPressed();
        }
    }

    private class MediaPagerAdapter extends FragmentPagerAdapter {
        public MediaPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        public int getCount() {
            return getListables().size();
        }

        public Fragment getItem(int position) {
            Fragment frag = new ItemDetailFragment();
            Bundle args = new Bundle();
            args.putInt(SEL_ITEM_INDEX, position);
            args.putBoolean(GRAB_FOCUS, true);
            frag.setArguments(args);
            return frag;
        }
    }

    @Override
    public void sort() throws IOException, JSONException, ParseException {
        super.sort();
        pager.setCurrentItem(0);
        positionBar.setProgress(1);
    }
}
