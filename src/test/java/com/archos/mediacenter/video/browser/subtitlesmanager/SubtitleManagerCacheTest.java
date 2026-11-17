// Copyright 2026 Courville Software
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.archos.mediacenter.video.browser.subtitlesmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

/**
 * The subtitle cache is static and PlayerService feeds it once per playback, so on a TV that is
 * never rebooted an uncapped map pins one SubtitleCacheEntry (holding a VideoMetadata copy) per
 * video forever. It is now a bounded, access-ordered LRU; these tests pin that behaviour.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SubtitleManagerCacheTest {

    @SuppressWarnings("unchecked")
    private static Map<String, ?> cache() throws Exception {
        Field f = SubtitleManager.class.getDeclaredField("mSubtitleCache");
        f.setAccessible(true);
        return (Map<String, ?>) f.get(null);
    }

    private static int declaredCap() throws Exception {
        Field f = SubtitleManager.class.getDeclaredField("CACHE_MAX_ENTRIES");
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static Uri uriFor(int index) {
        return Uri.parse("file:///movies/video" + index + ".mkv");
    }

    private static void cacheVideo(int index) {
        SubtitleManager.cacheSubtitleFiles(uriFor(index), new ArrayList<>());
    }

    @Before
    public void emptyCache() throws Exception {
        cache().clear();
        assertTrue(cache().isEmpty());
    }

    @After
    public void drainMainLooper() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void capMatchesDeclaredMaximum() throws Exception {
        assertEquals(50, declaredCap());
    }

    /** Filling well past the cap must stop at the cap instead of growing per video. */
    @Test
    public void cacheStaysBoundedWhenMoreVideosThanCapAreCached() throws Exception {
        for (int i = 0; i < 150; i++) {
            cacheVideo(i);
            assertTrue("cache grew past the cap at " + i, cache().size() <= declaredCap());
        }
        assertEquals(declaredCap(), cache().size());
    }

    /** Inserting in order means the first videos inserted are the first to be dropped. */
    @Test
    public void oldestEntriesAreEvictedFirst() throws Exception {
        for (int i = 0; i < 50; i++) {
            cacheVideo(i);
        }
        assertEquals(50, cache().size());

        cacheVideo(50);

        assertEquals(50, cache().size());
        assertNull("video0 should have been evicted", SubtitleManager.getCachedSubtitleFiles(uriFor(0)));
        assertNotNull("video1 should still be cached", SubtitleManager.getCachedSubtitleFiles(uriFor(1)));
        assertNotNull("the new entry must be cached", SubtitleManager.getCachedSubtitleFiles(uriFor(50)));
    }

    /**
     * The map is access ordered, not insertion ordered: a cached video that is still being used
     * must survive, and eviction must fall on the genuinely least recently used entry.
     */
    @Test
    public void readingAnEntryPromotesItAheadOfNewerUnusedEntries() throws Exception {
        for (int i = 0; i < 50; i++) {
            cacheVideo(i);
        }

        // touch video0, so video1 becomes the least recently used entry
        assertNotNull(SubtitleManager.getCachedSubtitleFiles(uriFor(0)));

        cacheVideo(50);

        assertEquals(50, cache().size());
        assertNotNull("a recently read entry must not be evicted",
                SubtitleManager.getCachedSubtitleFiles(uriFor(0)));
        assertNull("the least recently used entry must be evicted instead",
                SubtitleManager.getCachedSubtitleFiles(uriFor(1)));
    }

    /**
     * cacheProcessedMetadata() looks the entry up before writing it, and that lookup counts as an
     * access, so a video whose AVOS metadata was just cached is never the eviction victim.
     */
    @Test
    public void cachingMetadataPromotesTheEntryAheadOfEviction() throws Exception {
        for (int i = 0; i < 50; i++) {
            cacheVideo(i);
        }

        SubtitleManager.cacheProcessedMetadata(uriFor(0), null);

        cacheVideo(50);

        assertEquals(50, cache().size());
        assertNotNull("re-caching metadata must protect the entry from eviction",
                SubtitleManager.getCachedSubtitleFiles(uriFor(0)));
        assertNull("the least recently used entry is evicted instead",
                SubtitleManager.getCachedSubtitleFiles(uriFor(1)));
        assertNotNull(SubtitleManager.getCachedSubtitleFiles(uriFor(2)));
    }

    /** An evicted video must read back as a full miss, so nothing stays pinned through its key. */
    @Test
    public void evictedVideoReportsNoCachedMetadata() throws Exception {
        for (int i = 0; i < 50; i++) {
            cacheVideo(i);
        }

        cacheVideo(50); // no reads happened, so video0 is the eldest entry

        assertEquals(50, cache().size());
        assertTrue("evicted key must be gone from the map", !cache().containsKey(uriFor(0).toString()));
        assertNull(SubtitleManager.getCachedSubtitleFiles(uriFor(0)));
        assertNull(SubtitleManager.getCachedProcessedMetadata(uriFor(0)));
        assertNotNull("the surviving neighbour must be untouched",
                SubtitleManager.getCachedSubtitleFiles(uriFor(1)));
    }

    /** Re-caching the same video must replace its entry rather than add a second one. */
    @Test
    public void recachingTheSameVideoDoesNotGrowTheCache() throws Exception {
        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < 10; i++) {
                cacheVideo(i);
            }
        }
        assertEquals(10, cache().size());
    }

    /** A long browsing session must never hold more entries than the cap allows. */
    @Test
    public void mixedChurnNeverExceedsCap() throws Exception {
        for (int step = 0; step < 1000; step++) {
            cacheVideo(step % 137);
            if (step % 3 == 0) {
                SubtitleManager.getCachedSubtitleFiles(uriFor(step % 89));
            }
            if (step % 7 == 0) {
                SubtitleManager.invalidateCache(uriFor(step % 61));
            }
            assertTrue("cache exceeded the cap at step " + step, cache().size() <= declaredCap());
        }
    }

    /** invalidateCache must still remove an entry while the map stays bounded. */
    @Test
    public void invalidateCacheRemovesEntry() throws Exception {
        cacheVideo(0);
        cacheVideo(1);
        assertEquals(2, cache().size());

        SubtitleManager.invalidateCache(uriFor(0));

        assertEquals(1, cache().size());
        assertNull(SubtitleManager.getCachedSubtitleFiles(uriFor(0)));
        assertNotNull(SubtitleManager.getCachedSubtitleFiles(uriFor(1)));
    }
}
