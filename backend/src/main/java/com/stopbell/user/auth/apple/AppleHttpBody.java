package com.stopbell.user.auth.apple;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

final class AppleHttpBody {
    private AppleHttpBody() { }

    static byte[] read(HttpURLConnection connection, InputStream input, int maxBytes, int timeoutMillis)
            throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("Apple response deadline exceeded");
            long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
            connection.setReadTimeout((int) Math.min(Integer.MAX_VALUE, millis));
            int count = input.read(buffer, 0, Math.min(buffer.length, maxBytes + 1 - output.size()));
            if (count < 0) return output.toByteArray();
            output.write(buffer, 0, count);
            if (output.size() > maxBytes) throw new IOException("Apple response too large");
        }
    }
}
