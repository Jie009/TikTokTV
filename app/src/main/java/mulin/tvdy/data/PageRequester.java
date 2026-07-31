package mulin.tvdy.data;

/**
 * Implemented by whatever owns the hidden data-pump WebView. Lets
 * {@link FeedRepository} ask for more data without knowing anything about
 * WebViews or simulated key events.
 */
public interface PageRequester {
    void requestNextPage();
}
