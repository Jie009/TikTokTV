package mulin.tvdy.pump;

import android.net.Uri;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * JS injected into the hidden douyin.com WebView. Pulled out of
 * {@link FeedPumpController} into plain string constants purely so that class
 * isn't dominated by string-building noise.
 */
final class FeedHookScripts {

    private FeedHookScripts() {
    }

    /**
     * Hooks fetch/XHR so every response the real page receives for itself
     * gets mirrored back to native code via {@code window.AndroidBridge}.
     * Idempotent (guarded by {@code window.__tvdyPumpHooked}) - safe to
     * inject from document-start, {@code onPageStarted}, and
     * {@code onPageFinished}.
     */
    static final String INSTALL_HOOKS =
            "(function(){"
                    + "if(window.__tvdyPumpHooked)return;window.__tvdyPumpHooked=true;"
                    + "try{"
                    + "var meta=document.querySelector('meta[name=viewport]');"
                    + "if(!meta){meta=document.createElement('meta');meta.name='viewport';(document.head||document.documentElement).appendChild(meta);}"
                    + "meta.content='width=1280,initial-scale=1';"
                    + "}catch(e){}"
                    + "try{"
                    + "var RealAC=window.AudioContext||window.webkitAudioContext;"
                    + "if(RealAC){"
                    + "var patchedResume=function(){try{this.suspend();}catch(e){}return Promise.resolve();};"
                    + "RealAC.prototype.resume=patchedResume;"
                    + "function PatchedAC(){"
                    + "var ctx=new RealAC();"
                    + "try{ctx.suspend();}catch(e){}"
                    + "return ctx;"
                    + "}"
                    + "PatchedAC.prototype=RealAC.prototype;"
                    + "window.AudioContext=PatchedAC;"
                    + "if(window.webkitAudioContext)window.webkitAudioContext=PatchedAC;"
                    + "}"
                    + "}catch(e){}"
                    + "function neutralizeVideos(){"
                    + "try{document.querySelectorAll('video,audio').forEach(function(v){"
                    + "try{v.pause();}catch(e){}"
                    + "try{v.autoplay=false;v.removeAttribute('autoplay');}catch(e){}"
                    + "try{v.muted=true;v.volume=0;}catch(e){}"
                    + "try{if(v.getAttribute('src')){v.removeAttribute('src');v.load();}}catch(e){}"
                    + "});}catch(e){}"
                    + "}"
                    + "var __ntzScheduled=false;"
                    + "function neutralizeVideosDebounced(){"
                    + "if(__ntzScheduled)return;"
                    + "__ntzScheduled=true;"
                    + "setTimeout(function(){__ntzScheduled=false;neutralizeVideos();},500);"
                    + "}"
                    + "setInterval(neutralizeVideos,1000);"
                    + "neutralizeVideos();"
                    + "function isApiUrl(url){"
                    + "return /aweme\\/v\\d+\\/(web|pc)\\//.test(String(url||''))"
                    + "||/\\/aweme\\/v\\d+\\//.test(String(url||''));"
                    + "}"
                    + "function isFeedReportUrl(url){"
                    + "var u=String(url||'');"
                    + "return /\\/tab\\/feed/i.test(u)||/\\/module\\/feed/i.test(u);"
                    + "}"
                    + "function isHistoryReportUrl(url){"
                    + "return /\\/history\\/read/i.test(String(url||''));"
                    + "}"
                    + "function shouldReport(url,text){"
                    + "var u=String(url||'');"
                    + "var body=String(text||'');"
                    + "if(isFeedReportUrl(u)&&body.indexOf('\"aweme_list\"')>=0)return true;"
                    + "if(isHistoryReportUrl(u)&&body.indexOf('\"aweme_list\"')>=0)return true;"
                    + "return /\\/aweme\\/v\\d+\\/(web|pc)\\/[^?]*feed/i.test(u)"
                    + "||/\\/aweme\\/v\\d+\\/(web|pc)\\/module\\/feed/i.test(u)"
                    + "||/\\/aweme\\/v\\d+\\/(web|pc)\\/tab\\/feed/i.test(u);"
                    + "}"
                    + "function seen(url,note){"
                    + "try{if(isApiUrl(url)&&window.AndroidBridge){window.AndroidBridge.onUrlSeen(String(url),String(note||''));}}catch(e){}"
                    + "}"
                    + "function report(url,text){"
                    + "try{"
                    + "var body=String(text||'');"
                    + "if(!body)return;"
                    + "var u=String(url||'');"
                    + "if(body.indexOf('\"aweme_list\"')>=0){"
                    + "if(isFeedReportUrl(u)&&window.AndroidBridge){"
                    + "window.AndroidBridge.onFeedData(u,body);"
                    + "return;"
                    + "}"
                    + "if(isHistoryReportUrl(u)&&window.AndroidBridge){"
                    + "window.AndroidBridge.onHistoryData(u,body);"
                    + "return;"
                    + "}"
                    + "return;"
                    + "}"
                    + "if(shouldReport(u,body)&&window.AndroidBridge){"
                    + "window.AndroidBridge.onFeedData(u,body);"
                    + "}"
                    + "}catch(e){}"
                    + "}"
                    + "var originalFetch=window.fetch;"
                    + "if(originalFetch){"
                    + "window.fetch=function(){"
                    + "var args=arguments;"
                    + "return originalFetch.apply(this,args).then(function(resp){"
                    + "try{"
                    + "var input=args[0];"
                    + "var url=(input&&input.url)?input.url:input;"
                    + "seen(url,'fetch status='+resp.status);"
                    + "resp.clone().text().then(function(text){report(url,text);}).catch(function(err){seen(url,'fetch body read failed: '+err);});"
                    + "}catch(e){}"
                    + "return resp;"
                    + "});"
                    + "};"
                    + "}"
                    + "var XHROpen=XMLHttpRequest.prototype.open;"
                    + "var XHRSend=XMLHttpRequest.prototype.send;"
                    + "XMLHttpRequest.prototype.open=function(method,url){"
                    + "this.__tvdyUrl=url;"
                    + "return XHROpen.apply(this,arguments);"
                    + "};"
                    + "XMLHttpRequest.prototype.send=function(){"
                    + "var xhr=this;"
                    + "xhr.addEventListener('load',function(){"
                    + "try{"
                    + "var url=xhr.__tvdyUrl;"
                    + "var rt=xhr.responseType;"
                    + "seen(url,'xhr status='+xhr.status+' responseType='+JSON.stringify(rt));"
                    + "if(rt===''||rt==='text'){"
                    + "report(url,xhr.responseText);"
                    + "}else if(rt==='json'){"
                    + "try{report(url,JSON.stringify(xhr.response));}catch(e){seen(url,'json stringify failed');}"
                    + "}else if(rt==='arraybuffer'){"
                    + "try{report(url,new TextDecoder('utf-8').decode(new Uint8Array(xhr.response)));}catch(e){seen(url,'arraybuffer decode failed');}"
                    + "}else if(rt==='blob'){"
                    + "try{"
                    + "var reader=new FileReader();"
                    + "reader.onload=function(){try{report(url,String(reader.result||''));}catch(e){}};"
                    + "reader.readAsText(xhr.response);"
                    + "}catch(e){seen(url,'blob read failed');}"
                    + "}"
                    + "}catch(e){}"
                    + "});"
                    + "return XHRSend.apply(this,arguments);"
                    + "};"
                    + "function installObserver(){"
                    + "try{"
                    + "if(document.documentElement){"
                    + "new MutationObserver(neutralizeVideosDebounced).observe(document.documentElement,{childList:true,subtree:true});"
                    + "console.log('tvdy-pump hooks installed');"
                    + "}else{setTimeout(installObserver,50);}"
                    + "}catch(e){console.log('tvdy-pump observer install failed: '+e);}"
                    + "}"
                    + "installObserver();"
                    + "})();";

    /** Reports viewport size and whether the page looks like a login/captcha wall. */
    static final String PROBE_PAGE_STATE =
            "(function(){"
                    + "try{"
                    + "var body=(document.body&&document.body.innerText)||'';"
                    + "var info={"
                    + "w:window.innerWidth,h:window.innerHeight,"
                    + "title:document.title||'',"
                    + "hooked:!!window.__tvdyPumpHooked,"
                    + "videos:document.querySelectorAll('video').length,"
                    + "loginHint:body.indexOf('\\u767b\\u5f55')>=0||body.indexOf('\\u626b\\u7801')>=0,"
                    + "captchaHint:body.indexOf('\\u9a8c\\u8bc1')>=0"
                    + "};"
                    + "if(window.AndroidBridge){window.AndroidBridge.onPageProbe(JSON.stringify(info));}"
                    + "}catch(e){"
                    + "try{if(window.AndroidBridge){window.AndroidBridge.onPageProbe(JSON.stringify({error:String(e)}));}}catch(x){}"
                    + "}"
                    + "})();";

    /**
     * Nudge douyin's page into loading the recommend feed: fix viewport,
     * click the recommend tab if present, then simulate scroll/key.
     */
    static final String TRIGGER_INITIAL_FEED =
            "(function(){"
                    + "try{"
                    + "var meta=document.querySelector('meta[name=viewport]');"
                    + "if(!meta){meta=document.createElement('meta');meta.name='viewport';(document.head||document.documentElement).appendChild(meta);}"
                    + "meta.content='width=1280,initial-scale=1';"
                    + "}catch(e){}"
                    + "try{"
                    + "var nodes=document.querySelectorAll('a,span,div,button');"
                    + "for(var i=0;i<nodes.length&&i<300;i++){"
                    + "var t=(nodes[i].innerText||'').trim();"
                    + "if(t==='\\u63a8\\u8350'||t==='\\u9996\\u9875'){nodes[i].click();break;}"
                    + "}"
                    + "}catch(e){}"
                    + "function fire(type){"
                    + "var e=new KeyboardEvent(type,{key:'ArrowDown',code:'ArrowDown',keyCode:40,which:40,bubbles:true,cancelable:true});"
                    + "var t=document.activeElement||document.body||document.documentElement;"
                    + "try{t.dispatchEvent(e);}catch(err){}"
                    + "try{document.dispatchEvent(e);}catch(err){}"
                    + "try{window.dispatchEvent(e);}catch(err){}"
                    + "}"
                    + "fire('keydown');"
                    + "setTimeout(function(){fire('keyup');},30);"
                    + "setTimeout(function(){"
                    + "var amount=Math.max(window.innerHeight||720,document.documentElement.clientHeight||720,720);"
                    + "try{window.scrollBy({top:amount,behavior:'auto'});}catch(err){window.scrollBy(0,amount);}"
                    + "try{window.scrollTo(0,amount);}catch(err){}"
                    + "},150);"
                    + "})();";

    static final String REQUEST_NEXT_PAGE = TRIGGER_INITIAL_FEED;

    /**
     * Stronger scroll/key sequence for pagination — Argus rejects manually-signed
     * {@code refresh_index>=2} requests, so we nudge the page to fetch itself.
     */
    static final String TRIGGER_PAGE_FEED =
            "(function(){"
                    + "try{"
                    + "var meta=document.querySelector('meta[name=viewport]');"
                    + "if(!meta){meta=document.createElement('meta');meta.name='viewport';(document.head||document.documentElement).appendChild(meta);}"
                    + "meta.content='width=1280,initial-scale=1';"
                    + "}catch(e){}"
                    + "function fireOn(el,type){"
                    + "var e=new KeyboardEvent(type,{key:'ArrowDown',code:'ArrowDown',keyCode:40,which:40,bubbles:true,cancelable:true});"
                    + "try{el.dispatchEvent(e);}catch(err){}"
                    + "}"
                    + "function fire(type){"
                    + "var targets=[document.querySelector('[data-e2e=\"feed-list\"]'),"
                    + "document.querySelector('[data-e2e=\"feed-active-video\"]'),"
                    + "document.querySelector('main'),document.activeElement,"
                    + "document.body,document.documentElement];"
                    + "for(var i=0;i<targets.length;i++){if(targets[i])fireOn(targets[i],type);}"
                    + "try{window.dispatchEvent(new KeyboardEvent(type,{key:'ArrowDown',code:'ArrowDown',keyCode:40,which:40,bubbles:true,cancelable:true}));}catch(err){}"
                    + "}"
                    + "var nextBtn=document.querySelector('[data-e2e=\"video-switch-next-arrow\"]')"
                    + "||document.querySelector('[data-e2e=\"scroll-next\"]')"
                    + "||document.querySelector('button[aria-label*=\"\\u4e0b\\u4e00\\u6761\"]');"
                    + "if(nextBtn){try{nextBtn.click();}catch(err){}}"
                    + "fire('keydown');fire('keyup');"
                    + "var amount=Math.max(window.innerHeight||720,document.documentElement.clientHeight||720,720);"
                    + "try{window.scrollBy({top:amount,behavior:'auto'});}catch(err){window.scrollBy(0,amount);}"
                    + "try{window.scrollTo(0,document.body.scrollHeight||amount);}catch(err){}"
                    + "setTimeout(function(){fire('keydown');fire('keyup');},200);"
                    + "setTimeout(function(){"
                    + "try{window.scrollBy({top:amount,behavior:'auto'});}catch(err){window.scrollBy(0,amount);}"
                    + "if(nextBtn){try{nextBtn.click();}catch(err){}}"
                    + "fire('keydown');fire('keyup');"
                    + "},500);"
                    + "console.log('tvdy-pump scroll pagination');"
                    + "})();";

    /**
     * Builds a signed GET for the next feed page. Reuses params from the last
     * captured URL but strips stale signatures. {@code /tab/feed} paginates via
     * {@code refresh_index}/{@code pull_type} only — never injects {@code max_cursor}.
     */
    static String buildProactiveFetchScript(String lastUrl, long maxCursor, int refreshIndex) {
        String pathAndQuery = buildNextFeedPathAndQuery(lastUrl, maxCursor, refreshIndex);
        if (pathAndQuery == null) {
            return TRIGGER_INITIAL_FEED;
        }
        // refresh_index>=2: manual acrawler signing is rejected by Argus (403).
        // Delegate to the page's own fetch/XHR pipeline so secsdk can sign.
        if (refreshIndex >= 2) {
            return buildDelegateGetScript(pathAndQuery);
        }
        return buildSignedGetScript(pathAndQuery);
    }

    /**
     * Signed GET for a cold recommend feed ({@code pull_type=0}) — used after
     * login so the first batch is requested as a fresh page rather than
     * replaying whatever anonymous feed the WebView already cached.
     */
    static String buildFreshFeedScript() {
        return buildSignedGetScript("/aweme/v1/web/tab/feed/?"
                + "device_platform=webapp&aid=6383&channel=channel_pc_web&pc_client_type=1"
                + "&version_code=290100&version_name=29.1.0&cookie_enabled=true"
                + "&screen_width=1280&screen_height=720&browser_language=zh-CN"
                + "&browser_platform=Win32&browser_name=Chrome&browser_version=131.0.0.0"
                + "&browser_online=true&engine_name=Blink&engine_version=131.0.0.0"
                + "&os_name=Windows&os_version=10&platform=PC"
                + "&count=10&refresh_index=1&pull_type=0&video_type_select=1"
                + "&aweme_pc_rec_raw_data=%7B%22is_client%22%3A%22false%22%7D");
    }

    /** Paginated account watch-history sync ({@code /history/read/}). */
    static String buildFetchHistoryScript(long maxCursor) {
        return buildSignedGetScript("/aweme/v1/web/history/read/?"
                + "device_platform=webapp&aid=6383&channel=channel_pc_web&pc_client_type=1"
                + "&version_code=290100&version_name=29.1.0&cookie_enabled=true"
                + "&screen_width=1280&screen_height=720&browser_language=zh-CN"
                + "&browser_platform=Win32&browser_name=Chrome&browser_version=131.0.0.0"
                + "&browser_online=true&engine_name=Blink&engine_version=131.0.0.0"
                + "&os_name=Windows&os_version=10&platform=PC"
                + "&count=20&max_cursor=" + maxCursor + "&min_cursor=0", false);
    }

    /** Best-effort play report so Douyin's recommender learns what this app played. */
    static String buildReportPlayScript(String awemeId, long playMs) {
        return "(function(){"
                + "try{"
                + "var id=" + jsString(awemeId) + ";"
                + "var ms=" + playMs + ";"
                + "if(!id)return;"
                + "var body='item_id='+encodeURIComponent(id)"
                + "+'&aweme_type=0&tab_type=0&play_delta=1'"
                + "+'&pre_item_playtime='+Math.max(0,Math.floor(ms));"
                + "fetch('/aweme/v1/web/aweme/stats/?device_platform=webapp&aid=6383',{"
                + "method:'POST',credentials:'include',"
                + "headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                + "body:body"
                + "}).catch(function(){});"
                + "}catch(e){}"
                + "})();";
    }

    private static String buildNextFeedPathAndQuery(String lastUrl, long maxCursor, int refreshIndex) {
        try {
            Uri uri = Uri.parse(lastUrl);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return null;

            boolean tabFeed = path.contains("/tab/feed");
            Set<String> strip = new HashSet<>(Arrays.asList(
                    "a_bogus", "X-Bogus", "x-bogus", "x-secsdk-web-signature",
                    "timestamp", "max_cursor", "min_cursor", "refresh_index", "pull_type"));

            StringBuilder query = new StringBuilder();
            boolean first = true;
            for (String name : uri.getQueryParameterNames()) {
                if (strip.contains(name)) continue;
                for (String value : uri.getQueryParameters(name)) {
                    if (value == null) continue;
                    if (!first) query.append('&');
                    query.append(encodeQuery(name)).append('=').append(encodeQuery(value));
                    first = false;
                }
            }
            if (!first) query.append('&');
            query.append("refresh_index=").append(refreshIndex);
            query.append("&pull_type=").append(refreshIndex <= 1 ? "0" : "2");
            if (!tabFeed && maxCursor > 0) {
                query.append("&max_cursor=").append(maxCursor);
            }
            return path + "?" + query;
        } catch (Exception e) {
            return null;
        }
    }

    private static String encodeQuery(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    private static String buildSignedGetScript(String pathAndQuery) {
        return buildSignedGetScript(pathAndQuery, true);
    }

    /** Absolute base for API URLs — location.origin is often invalid during early load. */

    /**
     * Unsigned GET through the page's live fetch/XHR stack so secsdk can sign
     * pagination requests that manual acrawler signing cannot pass (403).
     */
    static String buildDelegateGetScript(String pathAndQuery) {
        return URL_HELPERS
                + "(function(){"
                + "try{"
                + "var path=" + jsString(pathAndQuery) + ";"
                + "var u=tvdyMakeUrl(path);"
                + "var finalUrl=u.toString();"
                + "console.log('tvdy-pump delegate GET '+finalUrl.substring(0,Math.min(finalUrl.length,140)));"
                + "var xhr=new XMLHttpRequest();"
                + "xhr.open('GET',finalUrl,true);"
                + "xhr.withCredentials=true;"
                + "xhr.onload=function(){"
                + "if(xhr.status>=400){"
                + "if(window.AndroidBridge){window.AndroidBridge.onProactiveFetchFailed('HTTP '+xhr.status);}"
                + "}else if(window.AndroidBridge){window.AndroidBridge.onProactiveFetchOk(finalUrl);}"
                + "};"
                + "xhr.onerror=function(){"
                + "if(window.AndroidBridge){window.AndroidBridge.onProactiveFetchFailed('xhr error');}"
                + "};"
                + "xhr.send();"
                + "}catch(e){"
                + "if(window.AndroidBridge){window.AndroidBridge.onProactiveFetchFailed(String(e));}"
                + "}"
                + "})();";
    }

    private static String buildSignedGetScript(String pathAndQuery, boolean feedRequest) {
        return SIGN_HELPERS + URL_HELPERS
                + "(function(){"
                + "try{"
                + "var feedReq=" + feedRequest + ";"
                + "var path=" + jsString(pathAndQuery) + ";"
                + "var u=tvdyMakeUrl(path);"
                + "if(!tvdySignGetUrl(u)){"
                + "if(feedReq&&window.AndroidBridge){window.AndroidBridge.onProactiveFetchFailed('no signer');}"
                + "return;"
                + "}"
                + "var finalUrl=u.toString();"
                + "console.log('tvdy-pump signed GET '+finalUrl.substring(0,Math.min(finalUrl.length,140)));"
                + "fetch(finalUrl,{credentials:'include',referrer:'https://www.douyin.com/'})"
                + ".then(function(resp){return resp.text().then(function(text){"
                + "if(!resp.ok){"
                + "if(feedReq&&window.AndroidBridge){window.AndroidBridge.onProactiveFetchFailed('HTTP '+resp.status);}"
                + "return;"
                + "}"
                + "if(feedReq&&window.AndroidBridge){window.AndroidBridge.onProactiveFetchOk(finalUrl);}"
                + "});})"
                + ".catch(function(err){"
                + "if(feedReq&&window.AndroidBridge){window.AndroidBridge.onProactiveFetchFailed(String(err));}"
                + "});"
                + "}catch(e){"
                + "if(feedReq&&window.AndroidBridge){window.AndroidBridge.onProactiveFetchFailed(String(e));}"
                + "}"
                + "})();";
    }

    static final String URL_HELPERS =
            "function tvdyPageOrigin(){"
                    + "try{"
                    + "var o=location.origin;"
                    + "if(o&&o!=='null'&&location.protocol!=='about:'&&location.protocol!=='file:')return o;"
                    + "}catch(e){}"
                    + "return 'https://www.douyin.com';"
                    + "}"
                    + "function tvdyMakeUrl(path){"
                    + "var p=String(path||'');"
                    + "if(/^https?:\\/\\//i.test(p))return new URL(p);"
                    + "return new URL(p,tvdyPageOrigin());"
                    + "}";

    /** Returns true when byted_acrawler is loaded and can sign cold-feed requests. */
    static final String SIGNER_READY =
            "(function(){"
                    + "try{"
                    + "return !!(window.byted_acrawler"
                    + "&&(typeof window.byted_acrawler.frontierSign==='function'"
                    + "||typeof window.byted_acrawler.sign==='function'));"
                    + "}catch(e){return false;}"
                    + "})();";

    /** Shared signing helpers injected before each signed GET. */
    static final String SIGN_HELPERS =
            "function tvdyApplyMsToken(u){"
                    + "try{"
                    + "var c=document.cookie||'';"
                    + "var p='msToken=';"
                    + "var i=c.indexOf(p);"
                    + "if(i>=0){"
                    + "var v=c.substring(i+p.length);"
                    + "var s=v.indexOf(';');"
                    + "u.searchParams.set('msToken',s>=0?v.substring(0,s):v);"
                    + "}"
                    + "}catch(e){}"
                    + "}"
                    + "function tvdyApplySignResult(u,signed){"
                    + "if(!signed)return false;"
                    + "var applied=false;"
                    + "if(typeof signed==='string'){"
                    + "if(signed.length<=40){u.searchParams.set('X-Bogus',signed);}"
                    + "else{u.searchParams.set('a_bogus',signed);}"
                    + "return true;"
                    + "}"
                    + "if(typeof signed!=='object')return false;"
                    + "if(signed['X-Bogus']){u.searchParams.set('X-Bogus',String(signed['X-Bogus']));applied=true;}"
                    + "if(signed.a_bogus){u.searchParams.set('a_bogus',String(signed.a_bogus));applied=true;}"
                    + "if(signed['x-secsdk-web-signature']){"
                    + "u.searchParams.set('x-secsdk-web-signature',String(signed['x-secsdk-web-signature']));applied=true;"
                    + "}"
                    + "return applied;"
                    + "}"
                    + "function tvdySignGetUrl(u){"
                    + "u.searchParams.delete('a_bogus');"
                    + "u.searchParams.delete('X-Bogus');"
                    + "u.searchParams.delete('x-bogus');"
                    + "u.searchParams.delete('x-secsdk-web-signature');"
                    + "u.searchParams.set('timestamp',String(Math.floor(Date.now()/1000)));"
                    + "tvdyApplyMsToken(u);"
                    + "var pathQuery=u.pathname+'?'+u.searchParams.toString();"
                    + "var ac=window.byted_acrawler;"
                    + "if(!ac)return false;"
                    + "var ok=false;"
                    + "if(typeof ac.frontierSign==='function'){"
                    + "try{ok=tvdyApplySignResult(u,ac.frontierSign({url:pathQuery}))||ok;}catch(e){}"
                    + "}"
                    + "pathQuery=u.pathname+'?'+u.searchParams.toString();"
                    + "if(typeof ac.sign==='function'){"
                    + "try{ok=tvdyApplySignResult(u,ac.sign({url:pathQuery}))||ok;}catch(e){}"
                    + "}"
                    + "try{"
                    + "if(window.secsdk&&window.secsdk.crypto&&typeof window.secsdk.crypto.sdkSign==='function'){"
                    + "var sdkSig=window.secsdk.crypto.sdkSign('web',pathQuery);"
                    + "if(sdkSig){u.searchParams.set('x-secsdk-web-signature',String(sdkSig));ok=true;}"
                    + "}"
                    + "}catch(e){}"
                    + "return ok&&(u.searchParams.has('X-Bogus')||u.searchParams.has('a_bogus'));"
                    + "}";

    private static String jsString(String value) {
        if (value == null) return "''";
        return "'"
                + value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                + "'";
    }
}
