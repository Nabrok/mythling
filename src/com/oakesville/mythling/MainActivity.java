/**
 * Copyright 2013 Donald Oakes
 * 
 * This file is part of Mythling.
 *
 * Mythling is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Mythling is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mythling.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.oakesville.mythling;

import java.io.IOException;

import org.json.JSONException;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.oakesville.mythling.app.AppData;
import com.oakesville.mythling.app.BadSettingsException;
import com.oakesville.mythling.app.Category;
import com.oakesville.mythling.app.MediaList;
import com.oakesville.mythling.app.MediaSettings.MediaType;
import com.oakesville.mythling.BuildConfig;
import com.oakesville.mythling.R;

public class MainActivity extends MediaActivity
{
  private static final String TAG = MainActivity.class.getSimpleName();
  
  private MediaList mediaList;
  public MediaList getMediaList() { return mediaList; }
  
  private ListView listView;
  public ListView getListView() { return listView; }
  
  private ArrayAdapter<Category> adapter;

  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    
    PreferenceManager.setDefaultValues(this, R.xml.cache_prefs, false);
    PreferenceManager.setDefaultValues(this, R.xml.credentials_prefs, false);
    PreferenceManager.setDefaultValues(this, R.xml.network_prefs, false);
    PreferenceManager.setDefaultValues(this, R.xml.playback_prefs, false);
    PreferenceManager.setDefaultValues(this, R.xml.quality_prefs, false);

    setContentView(R.layout.categories);
    
    createProgressBar();
    
    listView = (ListView) findViewById(R.id.categories);

  }
  
  @Override
  protected void onResume()
  {
    try
    {
      if (getAppData() == null || getAppData().isExpired())
        refresh();
      else
        populate();
    }
    catch (BadSettingsException ex)
    {
      stopProgress();
      Toast.makeText(getApplicationContext(), "Bad or missing setting:\n" + ex.getMessage(), Toast.LENGTH_LONG).show();
    }
    catch (Exception ex)
    {
      if (BuildConfig.DEBUG)
        Log.e(TAG, ex.getMessage(), ex);
      stopProgress();
      Toast.makeText(getApplicationContext(), "Error: " + ex.toString(), Toast.LENGTH_LONG).show();
    }
    
    super.onResume();
  }


  public void refresh() throws BadSettingsException
  {
    mediaList = new MediaList();
    adapter = new ArrayAdapter<Category>(this, android.R.layout.simple_list_item_1, android.R.id.text1, mediaList.getCategories().toArray(new Category[0]));
    listView.setAdapter(adapter);
    
    startProgress();
    getAppSettings().validate();
    
    refreshMediaList();
  }
  
  protected void populate() throws IOException, JSONException
  {
    startProgress();
    if (getAppData() == null)
    {
      AppData appData = new AppData(getApplicationContext());
      appData.readMediaList();
      setAppData(appData);
    }
    else if (getMediaType() != null && getMediaType() != getAppData().getMediaList().getMediaType())
    {
      // media type was changed, then back button was pressed
      getAppSettings().setMediaType(getMediaType());
      getAppSettings().setLastLoad(0);
      onResume();
    }
    
    mediaList = getAppData().getMediaList();
    if (mediaList.getMediaType() == MediaType.tv)
    {
      stopProgress();
      Uri.Builder builder = new Uri.Builder();
      builder.path("TV");
      Uri uri = builder.build();
      startActivity(new Intent(Intent.ACTION_VIEW, uri, getApplicationContext(),  MediaListActivity.class));
    }
    else
    {
      adapter = new ArrayAdapter<Category>(MainActivity.this, android.R.layout.simple_list_item_1, android.R.id.text1, mediaList.getCategories().toArray(new Category[0]));
      listView.setAdapter(adapter);
      listView.setOnItemClickListener(new OnItemClickListener()
      {
        public void onItemClick(AdapterView<?> parent, View view, int position, long id)
        {
          String cat = ((TextView)view).getText().toString();
          Uri.Builder builder = new Uri.Builder();
          builder.path(cat);
          Uri uri = builder.build();
          startActivity(new Intent(Intent.ACTION_VIEW, uri, getApplicationContext(),  MediaListActivity.class));
        }
      });
      stopProgress();
    }
  }  
  
}