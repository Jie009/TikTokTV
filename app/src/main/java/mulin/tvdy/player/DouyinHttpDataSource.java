package mulin.tvdy.player;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.util.List;
import java.util.Map;

import mulin.tvdy.DouyinConstants;

/**
 * Refreshes Douyin session cookies and Referer on every open() — the pump
 * WebView writes cookies after ExoPlayer is constructed, so a one-shot header
 * map on the factory would stay empty and CDN returns 403.
 */
@UnstableApi
final class DouyinHttpDataSource implements HttpDataSource {

    private final DefaultHttpDataSource delegate;

    private DouyinHttpDataSource(DefaultHttpDataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        String awemeId = DouyinPlaybackRegistry.findAwemeId(dataSpec.uri.toString());
        Map<String, String> headers = DouyinConstants.buildPlaybackRequestHeaders(awemeId);
        return delegate.open(dataSpec.withRequestHeaders(headers));
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
        return delegate.read(buffer, offset, length);
    }

    @Override
    @Nullable
    public android.net.Uri getUri() {
        return delegate.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return delegate.getResponseHeaders();
    }

    @Override
    public void close() throws HttpDataSourceException {
        delegate.close();
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        delegate.addTransferListener(transferListener);
    }

    @Override
    public void setRequestProperty(String name, String value) {
        delegate.setRequestProperty(name, value);
    }

    @Override
    public void clearRequestProperty(String name) {
        delegate.clearRequestProperty(name);
    }

    @Override
    public void clearAllRequestProperties() {
        delegate.clearAllRequestProperties();
    }

    @Override
    public int getResponseCode() {
        return delegate.getResponseCode();
    }

    @UnstableApi
    static final class Factory implements DataSource.Factory {

        private final DefaultHttpDataSource.Factory delegateFactory;

        Factory() {
            delegateFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(DouyinConstants.DESKTOP_USER_AGENT)
                    .setAllowCrossProtocolRedirects(true);
        }

        @Override
        public DouyinHttpDataSource createDataSource() {
            return new DouyinHttpDataSource(delegateFactory.createDataSource());
        }
    }
}
