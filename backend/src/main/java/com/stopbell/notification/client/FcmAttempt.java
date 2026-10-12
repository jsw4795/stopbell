package com.stopbell.notification.client;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.apache.v2.ApacheHttpTransport;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.entity.HttpEntityWrapper;

/** One invocation owns its sockets. Cancellation aborts the request and closes the connection pool. */
@SuppressWarnings("deprecation")
final class FcmAttempt extends CloseableHttpClient {
    private final CloseableHttpClient delegate;
    private final long deadline;
    private final int connectTimeout;
    private final int readTimeout;
    private HttpUriRequest active;
    private boolean closed;
    private boolean messaging;
    private int authCalls;
    private int messagingCalls;

    FcmAttempt(FcmClientProperties properties, long startedNanos) {
        deadline = startedNanos + properties.requestBudget().toNanos();
        connectTimeout = (int) properties.connectTimeout().toMillis();
        readTimeout = (int) properties.readTimeout().toMillis();
        delegate = HttpClients.custom()
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableAuthCaching()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    HttpTransport authTransport() {
        return new ApacheHttpTransport(this);
    }

    synchronized void beginMessaging() throws IOException {
        checkOpen();
        messaging = true;
    }

    synchronized boolean messagingStarted() {
        return messagingCalls > 0;
    }

    synchronized void checkOpen() throws IOException {
        if (closed || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            throw new IOException("FCM request budget exhausted");
        }
    }

    int remainingMillis() throws IOException {
        checkOpen();
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    @Override
    protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest request, HttpContext context)
            throws IOException {
        synchronized (this) {
            checkOpen();
            if (!(request instanceof HttpUriRequest uriRequest)
                    || (messaging ? ++messagingCalls : ++authCalls) > 1) {
                throw new IOException("FCM automatic retransmission blocked");
            }
            active = uriRequest;
            if (request instanceof HttpRequestBase base) {
                int remaining = remainingMillis();
                RequestConfig original = base.getConfig();
                base.setConfig((original == null ? RequestConfig.custom() : RequestConfig.copy(original))
                        .setConnectTimeout(Math.min(connectTimeout, remaining))
                        .setSocketTimeout(Math.min(readTimeout, remaining))
                        .setConnectionRequestTimeout(remaining)
                        .setAuthenticationEnabled(false).setRedirectsEnabled(false).build());
            }
        }
        // Apache MainClientExec checks the aborted request before sending, including after DNS/connect.
        CloseableHttpResponse response = delegate.execute(target, request, context);
        if (response.getEntity() != null) {
            var encoding = response.getEntity().getContentEncoding();
            boolean gzip = encoding != null && "gzip".equalsIgnoreCase(encoding.getValue());
            if (encoding != null && !gzip && !"identity".equalsIgnoreCase(encoding.getValue())) {
                response.close();
                throw new IOException("Unsupported FCM content encoding");
            }
            // Bound decoded bytes too, including Google OAuth's automatic gzip negotiation.
            response.removeHeaders("Content-Encoding");
            response.removeHeaders("Content-Length");
            response.setEntity(new HttpEntityWrapper(response.getEntity()) {
                @Override
                public org.apache.http.Header getContentEncoding() {
                    return null;
                }

                @Override
                public long getContentLength() {
                    return -1;
                }

                @Override
                public InputStream getContent() throws IOException {
                    InputStream content = super.getContent();
                    return new FilterInputStream(gzip ? new GZIPInputStream(content) : content) {
                        private int received;

                        @Override
                        public int read() throws IOException {
                            int value = super.read();
                            checkLimit(value < 0 ? 0 : 1);
                            return value;
                        }

                        @Override
                        public int read(byte[] bytes, int offset, int length) throws IOException {
                            int count = in.read(bytes, offset, Math.min(length, 16_385 - received));
                            checkLimit(Math.max(0, count));
                            return count;
                        }

                        private void checkLimit(int count) throws IOException {
                            received += count;
                            if (received > 16_384) {
                                throw new IOException("FCM response limit exceeded");
                            }
                        }
                    };
                }
            });
        }
        return response;
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            closed = true;
            if (active != null) {
                active.abort();
            }
        }
        delegate.close();
    }

    @Override
    public HttpParams getParams() {
        return delegate.getParams();
    }

    @Override
    public ClientConnectionManager getConnectionManager() {
        return delegate.getConnectionManager();
    }
}
