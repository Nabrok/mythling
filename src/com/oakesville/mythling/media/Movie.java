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
package com.oakesville.mythling.media;

import java.util.Comparator;

import com.oakesville.mythling.app.Localizer;
import com.oakesville.mythling.media.MediaSettings.MediaType;

public class Movie extends Video {
    public Movie(String id, String title) {
        super(id, title);
    }

    public MediaType getType() {
        return MediaType.movies;
    }

    public String getLabel() {
        String label = getTitle();
        if (getYear() > 0)
            label += " (" + getYear() + ")";
        return label;
    }

    @Override
    protected String getExtraText() {
        StringBuffer buf = new StringBuffer();
        if (getYear() > 0)
            buf.append(" (").append(getYear()).append(")");
        if (getRating() > 0)
            buf.append(" ").append(getRatingString(getRating()));
        return buf.toString();
    }

    @Override
    protected Comparator<Item> getDateComparator() {
        return new Comparator<Item>() {
            public int compare(Item item1, Item item2) {
                Movie movie1 = (Movie) item1;
                Movie movie2 = (Movie) item2;
                if (movie1.getYear() == movie2.getYear()) {
                    String t1 = Localizer.stripLeadingArticle(movie1.getTitle());
                    String t2 = Localizer.stripLeadingArticle(movie2.getTitle());
                    return t1.compareToIgnoreCase(t2);
                } else {
                    return movie1.getYear() - movie2.getYear();
                }
            }
        };
    }
}
