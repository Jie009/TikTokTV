package mulin.tvdy.data;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Offline POC: proves feed-shaped JSON with browser-style
 * {@code media-video-hvc1} + {@code media-audio-*} URLs is paired correctly
 * before wiring MergingMediaSource on device.
 */
public class FeedVideoSplitStreamTest {

    private static final String FID = "b75359efcd4ccdf61b821efbf08e5a2f";

    @Test
    public void pairsBrowserStyleSplitUrlsByFid() throws Exception {
        String videoUrl = "https://v3-web-prime.douyinvod.com/video/tos/cn/x/media-video-hvc1/"
                + "?a=6383&br=794&mime_type=video_mp4&fid=" + FID
                + "&signature=aaa";
        String audioUrl = "https://v3-web-prime.douyinvod.com/video/tos/cn/y/media-audio-und-mp4a/"
                + "?a=6383&br=47&mime_type=video_mp4&fid=" + FID
                + "&signature=bbb";
        String muxedHigh = "https://v26-web.douyinvod.com/muxed.mp4?br=2500&signature=ccc";

        JSONObject video = new JSONObject();
        video.put("play_addr", urlList(muxedHigh));
        JSONArray bitRate = new JSONArray();
        JSONObject gear = new JSONObject();
        gear.put("gear_name", "720_1_1");
        gear.put("bit_rate", 794000);
        gear.put("is_h265", 1);
        gear.put("play_addr", urlList(videoUrl, audioUrl));
        bitRate.put(gear);
        video.put("bit_rate", bitRate);

        List<FeedVideo.SplitPlayUrl> splits = FeedVideo.collectSplitPlayUrls(video);
        assertEquals(1, splits.size());
        assertEquals(videoUrl, splits.get(0).videoUrl);
        assertEquals(audioUrl, splits.get(0).audioUrl);
        assertEquals(794, splits.get(0).videoBrKbps);
        assertTrue(splits.get(0).hevc);

        JSONObject item = new JSONObject();
        item.put("aweme_id", "7123456789");
        item.put("desc", "poc");
        item.put("video", video);
        item.put("author", new JSONObject().put("nickname", "t"));
        item.put("statistics", new JSONObject());

        FeedVideo fv = FeedVideo.fromAwemeItem(item);
        assertNotNull(fv);
        assertTrue(fv.hasSplitStream());
        // Muxed candidates prefer lower br; high muxed still present as fallback.
        assertFalse(fv.playUrlCandidates.isEmpty());
        assertFalse(fv.playUrl.contains("media-video-"));
        assertFalse(fv.playUrl.contains("media-audio-"));
    }

    @Test
    public void prefersLowestBrSplitPair() throws Exception {
        String fid = "abc123";
        String vLow = "https://cdn.example/media-video-hvc1/?br=500&fid=" + fid;
        String vHigh = "https://cdn.example/media-video-hvc1/?br=2000&fid=" + fid;
        String audio = "https://cdn.example/media-audio-und-mp4a/?br=48&fid=" + fid;

        JSONObject video = new JSONObject();
        video.put("play_addr", urlList(vHigh, vLow, audio));

        List<FeedVideo.SplitPlayUrl> splits = FeedVideo.collectSplitPlayUrls(video);
        assertEquals(2, splits.size());
        assertEquals(500, splits.get(0).videoBrKbps);
        assertEquals(vLow, splits.get(0).videoUrl);
        assertEquals(audio, splits.get(0).audioUrl);
    }

    @Test
    public void muxedSortPrefersLowerBitrate() throws Exception {
        JSONObject video = new JSONObject();
        JSONArray bitRate = new JSONArray();
        bitRate.put(gear("1080", 2500000, 0,
                "https://v26-web.douyinvod.com/hi.mp4?br=2500&mime_type=video_mp4"));
        bitRate.put(gear("540", 600000, 0,
                "https://v26-web.douyinvod.com/lo.mp4?br=600&mime_type=video_mp4"));
        video.put("bit_rate", bitRate);
        video.put("play_addr", urlList(
                "https://www.douyin.com/aweme/v1/play/?video_id=x"));

        JSONObject item = new JSONObject();
        item.put("aweme_id", "1");
        item.put("video", video);

        FeedVideo fv = FeedVideo.fromAwemeItem(item);
        assertNotNull(fv);
        assertTrue(fv.playUrl.contains("lo.mp4") || fv.playUrl.contains("br=600"));
        assertEquals(600, Integer.parseInt(FeedVideo.extractQueryParam(fv.playUrlCandidates.get(0), "br")));
    }

    /**
     * Matches real feed dump: media-video-* present, but audio URLs live in
     * {@code bit_rate_audio} without a {@code media-audio-} path segment.
     */
    @Test
    public void pairsMediaVideoWithBitRateAudioField() throws Exception {
        String videoUrl = "https://v5-dy.example/tos/cn/x/media-video-hvc1/?br=424&fid=fid1";
        String audioUrl = "https://v5-dy.example/tos/cn/y/osAudioOnlyBlob/?br=48&fid=fid1";
        String muxedH264 = "https://v5-dy.example/muxed-h264.mp4?br=300";

        JSONObject video = new JSONObject();
        video.put("play_addr_h264", urlList(muxedH264));
        JSONArray bitRate = new JSONArray();
        bitRate.put(gear("1080_1_1", 434816, 1, videoUrl));
        video.put("bit_rate", bitRate);

        JSONArray bitRateAudio = new JSONArray();
        JSONObject audioEntry = new JSONObject();
        audioEntry.put("bit_rate", 48000);
        audioEntry.put("play_addr", urlList(audioUrl));
        bitRateAudio.put(audioEntry);
        video.put("bit_rate_audio", bitRateAudio);

        List<FeedVideo.SplitPlayUrl> splits = FeedVideo.collectSplitPlayUrls(video);
        assertFalse("expected split from media-video + bit_rate_audio", splits.isEmpty());
        assertEquals(videoUrl, splits.get(0).videoUrl);
        assertEquals(audioUrl, splits.get(0).audioUrl);

        JSONObject item = new JSONObject();
        item.put("aweme_id", "99");
        item.put("video", video);
        FeedVideo fv = FeedVideo.fromAwemeItem(item);
        assertNotNull(fv);
        assertTrue(fv.hasSplitStream());
        // Muxed fallback should prefer H.264 holder over HEVC gear play_addr.
        assertTrue(fv.playUrlCandidates.get(0).contains("muxed-h264")
                || fv.playUrl.contains("muxed-h264"));
    }

    /**
     * Matches 2026-08-01 emulator dump: {@code audio_meta.url_list} is an object
     * with {@code main_url}, {@code format=dash}, not a string array.
     */
    @Test
    public void pairsMediaVideoWithAudioMetaMainUrlObject() throws Exception {
        String videoUrl = "https://v5-dy-ov-experiment.zjcdn.com/aaa/video/tos/cn/x/media-video-avc1/"
                + "?br=518&mime_type=video_mp4";
        String audioUrl = "https://v5-dy-ov-experiment.zjcdn.com/bbb/video/tos/cn/tos-cn-ve-15c000-ce/"
                + "oQIp4eB1gNtjJi2A/?a=6383";
        String muxed = "https://v5-dy-ov-experiment.zjcdn.com/muxed.mp4?br=518";

        JSONObject video = new JSONObject();
        video.put("play_addr_h264", urlList(muxed));
        JSONArray bitRate = new JSONArray();
        bitRate.put(gear("low_720_0", 518000, 0, videoUrl));
        video.put("bit_rate", bitRate);

        JSONObject urlListObj = new JSONObject();
        urlListObj.put("main_url", audioUrl);
        urlListObj.put("backup_url", audioUrl + "&backup=1");

        JSONObject audioMeta = new JSONObject();
        audioMeta.put("bitrate", 48932);
        audioMeta.put("format", "dash");
        audioMeta.put("media_type", "audio");
        audioMeta.put("url_list", urlListObj);

        JSONObject audioEntry = new JSONObject();
        audioEntry.put("audio_meta", audioMeta);
        audioEntry.put("audio_extra", "{\"real_bitrate\":493208}");
        audioEntry.put("audio_quality", 0);

        video.put("bit_rate_audio", new JSONArray().put(audioEntry));

        List<FeedVideo.SplitPlayUrl> splits = FeedVideo.collectSplitPlayUrls(video);
        assertFalse("expected split from media-video + audio_meta.main_url", splits.isEmpty());
        assertEquals(videoUrl, splits.get(0).videoUrl);
        assertEquals(audioUrl, splits.get(0).audioUrl);
    }

    @Test
    public void muxedPrefersH264OverHevcEvenIfHevcLowerBr() throws Exception {
        JSONObject video = new JSONObject();
        JSONArray bitRate = new JSONArray();
        bitRate.put(gear("hevc_low", 200000, 1,
                "https://cdn.example/hevc-low.mp4?br=200"));
        JSONObject h264 = gear("h264_mid", 400000, 0,
                "https://cdn.example/h264-mid.mp4?br=400");
        h264.put("play_addr_h264", urlList("https://cdn.example/h264-mid.mp4?br=400"));
        bitRate.put(h264);
        video.put("bit_rate", bitRate);

        JSONObject item = new JSONObject();
        item.put("aweme_id", "2");
        item.put("video", video);
        FeedVideo fv = FeedVideo.fromAwemeItem(item);
        assertNotNull(fv);
        assertTrue(fv.playUrlCandidates.get(0).contains("h264-mid"));
    }

    private static JSONObject gear(String name, int bitRate, int isH265, String url)
            throws Exception {
        JSONObject g = new JSONObject();
        g.put("gear_name", name);
        g.put("bit_rate", bitRate);
        g.put("is_h265", isH265);
        g.put("play_addr", urlList(url));
        return g;
    }

    private static JSONObject urlList(String... urls) throws Exception {
        JSONObject addr = new JSONObject();
        JSONArray list = new JSONArray();
        for (String u : urls) list.put(u);
        addr.put("url_list", list);
        return addr;
    }
}
