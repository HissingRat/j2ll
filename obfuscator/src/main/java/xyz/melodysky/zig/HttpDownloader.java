package xyz.melodysky.zig;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class HttpDownloader {

    private static final long CONSOLE_DOWNLOAD_UPDATE_INTERVAL_MS = 50;
    private static final long FILE_DOWNLOAD_UPDATE_INTERVAL_MS = 1000;
    private static final long FILE_DOWNLOAD_UPDATE_BYTES = 1024L * 1024;

    private HttpDownloader() {
    }

    static void download(String url, Path destination, Path logFile, ProgressLog progressLog) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "j2ll");
        connection.connect();

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Failed to download Zig from " + url + ": HTTP " + status);
        }

        long contentLength = connection.getContentLengthLong();
        long downloaded = 0;
        int lastConsolePercent = -1;
        int lastLoggedPercent = -1;
        long lastLoggedBytes = 0;
        long downloadStartedAt = System.currentTimeMillis();
        long lastConsoleUpdateAt = 0;
        long lastFileUpdateAt = 0;

        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             OutputStream output = Files.newOutputStream(destination,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                downloaded += read;
                long now = System.currentTimeMillis();

                if (contentLength > 0) {
                    int percent = (int) Math.min(100, downloaded * 100 / contentLength);
                    String progressText = progressLog.formatDownloadProgress(downloaded, contentLength, percent, downloadStartedAt, now);
                    if (percent == 100 || (percent != lastConsolePercent &&
                            now - lastConsoleUpdateAt >= CONSOLE_DOWNLOAD_UPDATE_INTERVAL_MS)) {
                        progressLog.updateConsoleProgress(progressText);
                        lastConsolePercent = percent;
                        lastConsoleUpdateAt = now;
                    }
                    if (percent == 100 || (percent != lastLoggedPercent &&
                            ((now - lastFileUpdateAt >= FILE_DOWNLOAD_UPDATE_INTERVAL_MS &&
                                    downloaded - lastLoggedBytes >= FILE_DOWNLOAD_UPDATE_BYTES) ||
                                    percent >= lastLoggedPercent + 5))) {
                        progressLog.appendFileLog(logFile, progressText + System.lineSeparator());
                        lastLoggedPercent = percent;
                        lastLoggedBytes = downloaded;
                        lastFileUpdateAt = now;
                    }
                } else if (now - lastConsoleUpdateAt >= CONSOLE_DOWNLOAD_UPDATE_INTERVAL_MS) {
                    String progressText = progressLog.formatDownloadProgress(downloaded, -1, -1, downloadStartedAt, now);
                    progressLog.updateConsoleProgress(progressText);
                    lastConsoleUpdateAt = now;
                    if (now - lastFileUpdateAt >= FILE_DOWNLOAD_UPDATE_INTERVAL_MS &&
                            downloaded - lastLoggedBytes >= FILE_DOWNLOAD_UPDATE_BYTES) {
                        progressLog.appendFileLog(logFile, progressText + System.lineSeparator());
                        lastLoggedBytes = downloaded;
                        lastFileUpdateAt = now;
                    }
                }
            }
        } finally {
            connection.disconnect();
        }

        if (contentLength <= 0 && downloaded > lastLoggedBytes) {
            String progressText = progressLog.formatDownloadProgress(downloaded, -1, -1, downloadStartedAt, System.currentTimeMillis());
            progressLog.updateConsoleProgress(progressText);
            progressLog.appendFileLog(logFile, progressText + System.lineSeparator());
        }

        progressLog.clearConsoleProgress();
    }
}
