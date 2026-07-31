package mulin.tvdy.pump;

/**
 * JS injected into the hidden douyin.com WebView.
 * <p>
 * Feed capture is passive only: hooks mirror responses from the page's own
 * signed XHR/fetch. We never rebuild or re-sign feed URLs here.
 */
final class FeedHookScripts {

    private FeedHookScripts() {
    }

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
                    + "function seen(url,note){"
                    + "try{if(isApiUrl(url)&&window.AndroidBridge){window.AndroidBridge.onUrlSeen(String(url),String(note||''));}}catch(e){}"
                    + "}"
                    + "function tvdyFeedKey(obj){"
                    + "try{"
                    + "if(!obj||!obj.aweme_list||!obj.aweme_list.length)return'';"
                    + "var f=obj.aweme_list[0];"
                    + "return String(f.aweme_id||f.awemeId||'')+':'+obj.aweme_list.length;"
                    + "}catch(e){return'';}"
                    + "}"
                    + "function tvdyIsDuplicateFeed(key){"
                    + "if(!key)return false;"
                    + "var now=Date.now();"
                    + "if(window.__tvdyLastFeedKey===key&&now-(window.__tvdyLastFeedAt||0)<5000)return true;"
                    + "window.__tvdyLastFeedKey=key;window.__tvdyLastFeedAt=now;return false;"
                    + "}"
                    + "function report(url,text){"
                    + "try{"
                    + "var body=String(text||'');"
                    + "if(!body)return;"
                    + "var u=String(url||'');"
                    + "if(body.indexOf('\"aweme_list\"')>=0){"
                    + "try{"
                    + "var parsed=JSON.parse(body);"
                    + "if(tvdyIsDuplicateFeed(tvdyFeedKey(parsed)))return;"
                    + "}catch(e){}"
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
                    + "if(isFeedReportUrl(u)&&body.charAt(0)!=='{'&&window.AndroidBridge){"
                    + "window.AndroidBridge.onLiteFeedBinary(u);"
                    + "return;"
                    + "}"
                    + "}catch(e){}"
                    + "}"
                    + "function reportParsedFeed(obj){"
                    + "try{"
                    + "if(!obj||!obj.aweme_list||!obj.aweme_list.length)return;"
                    + "if(!window.AndroidBridge)return;"
                    + "if(tvdyIsDuplicateFeed(tvdyFeedKey(obj)))return;"
                    + "var body=JSON.stringify(obj);"
                    + "var u=window.__tvdyLastFeedUrl||'https://www.douyin.com/aweme/v2/web/module/feed/';"
                    + "if(isHistoryReportUrl(u)){window.AndroidBridge.onHistoryData(u,body);}"
                    + "else{window.AndroidBridge.onFeedData(u,body);}"
                    + "}catch(e){}"
                    + "}"
                    + "try{"
                    + "var __tvdyOrigParse=JSON.parse;"
                    + "JSON.parse=function(){"
                    + "var r=__tvdyOrigParse.apply(this,arguments);"
                    + "try{reportParsedFeed(r);}catch(e){}"
                    + "return r;"
                    + "};"
                    + "}catch(e){}"
                    + "var originalFetch=window.fetch;"
                    + "if(originalFetch){"
                    + "window.fetch=function(){"
                    + "var args=arguments;"
                    + "return originalFetch.apply(this,args).then(function(resp){"
                    + "try{"
                    + "var input=args[0];"
                    + "var url=(input&&input.url)?input.url:input;"
                    + "seen(url,'fetch status='+resp.status);"
                    + "if(resp.ok){"
                    + "resp.clone().text().then(function(text){report(url,text);}).catch(function(err){seen(url,'fetch body read failed: '+err);});"
                    + "}"
                    + "}catch(e){}"
                    + "return resp;"
                    + "});"
                    + "};"
                    + "}"
                    + "var XHROpen=XMLHttpRequest.prototype.open;"
                    + "var XHRSend=XMLHttpRequest.prototype.send;"
                    + "XMLHttpRequest.prototype.open=function(method,url){"
                    + "this.__tvdyUrl=url;"
                    + "try{"
                    + "var u=String(url||'');"
                    + "if(/\\/module\\/feed/i.test(u)||/\\/tab\\/feed/i.test(u)){window.__tvdyLastFeedUrl=u;}"
                    + "}catch(e){}"
                    + "return XHROpen.apply(this,arguments);"
                    + "};"
                    + "XMLHttpRequest.prototype.send=function(){"
                    + "var xhr=this;"
                    + "xhr.addEventListener('load',function(){"
                    + "try{"
                    + "var url=xhr.__tvdyUrl;"
                    + "var rt=xhr.responseType;"
                    + "seen(url,'xhr status='+xhr.status+' responseType='+JSON.stringify(rt));"
                    + "if(xhr.status!==200)return;"
                    + "if(rt===''||rt==='text'){"
                    + "report(url,xhr.responseText);"
                    + "}else if(rt==='json'){"
                    + "try{report(url,JSON.stringify(xhr.response));}catch(e){seen(url,'json stringify failed');}"
                    + "}else if(rt==='arraybuffer'){"
                    + "if(isFeedReportUrl(String(url||''))){"
                    + "if(window.AndroidBridge){window.AndroidBridge.onLiteFeedBinary(String(url||''));}"
                    + "}else{"
                    + "try{report(url,new TextDecoder('utf-8').decode(new Uint8Array(xhr.response)));}catch(e){seen(url,'arraybuffer decode failed');}"
                    + "}"
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

    /** Cold start: viewport fix, recommend tab click, light scroll. */
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
                    + "},150);"
                    + "console.log('tvdy-pump initial feed nudge');"
                    + "})();";

    /**
     * Pagination: scroll / next-button / key events so the page requests the
     * next feed batch with its own valid signatures.
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
                    + "console.log('tvdy-pump page pagination nudge');"
                    + "})();";

    /** Paginated account watch-history sync ({@code /history/read/}). */
    static String buildFetchHistoryScript(long maxCursor) {
        return SIGN_HELPERS + URL_HELPERS
                + "(function(){"
                + "try{"
                + "var path='/aweme/v1/web/history/read/?"
                + "device_platform=webapp&aid=6383&channel=channel_pc_web&pc_client_type=1"
                + "&version_code=290100&version_name=29.1.0&cookie_enabled=true"
                + "&screen_width=1280&screen_height=720&browser_language=zh-CN"
                + "&browser_platform=Win32&browser_name=Chrome&browser_version=131.0.0.0"
                + "&browser_online=true&engine_name=Blink&engine_version=131.0.0.0"
                + "&os_name=Windows&os_version=10&platform=PC"
                + "&count=20&max_cursor=" + maxCursor + "&min_cursor=0';"
                + "var u=tvdyMakeUrl(path);"
                + "if(!tvdySignGetUrl(u)){if(window.AndroidBridge){window.AndroidBridge.onHistoryFetchFailed('no signer');}return;}"
                + "fetch(u.toString(),{credentials:'include',referrer:'https://www.douyin.com/'})"
                + ".then(function(resp){return resp.text();})"
                + ".catch(function(err){if(window.AndroidBridge){window.AndroidBridge.onHistoryFetchFailed(String(err));}});"
                + "}catch(e){if(window.AndroidBridge){window.AndroidBridge.onHistoryFetchFailed(String(e));}}"
                + "})();";
    }

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
                    + "if(typeof signed==='string'){"
                    + "u.searchParams.delete('X-Bogus');"
                    + "u.searchParams.set('a_bogus',signed);"
                    + "return true;"
                    + "}"
                    + "if(typeof signed!=='object')return false;"
                    + "var applied=false;"
                    + "if(signed.a_bogus){u.searchParams.delete('X-Bogus');u.searchParams.set('a_bogus',String(signed.a_bogus));applied=true;}"
                    + "if(signed['X-Bogus']&&!u.searchParams.has('a_bogus')){u.searchParams.set('X-Bogus',String(signed['X-Bogus']));applied=true;}"
                    + "return applied;"
                    + "}"
                    + "function tvdySignGetUrl(u){"
                    + "u.searchParams.delete('a_bogus');"
                    + "u.searchParams.delete('X-Bogus');"
                    + "u.searchParams.delete('msToken');"
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
                    + "return ok&&u.searchParams.has('a_bogus');"
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
