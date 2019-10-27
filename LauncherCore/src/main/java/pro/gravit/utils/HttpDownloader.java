package pro.gravit.utils;

import pro.gravit.utils.helper.IOHelper;
import pro.gravit.utils.helper.LogHelper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class HttpDownloader {
    public static final int INTERVAL = 500;
    public final AtomicInteger writed;
    private volatile String filename;
    public final Thread thread;

    public HttpDownloader(URL url, Path file) {
        writed = new AtomicInteger(0);
        filename = null;
        thread = new Thread(() -> {
            try {
                filename = IOHelper.getFileName(file);
                downloadFile(url, file, writed::set);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    public String getFilename() {
        return filename;
    }

    public static void downloadFile(URL url, Path file, Consumer<Integer> chanheTrack) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(url.openStream()); OutputStream fout = IOHelper.newOutput(file, false)) {

            final byte[] data = new byte[IOHelper.BUFFER_SIZE];
            int count;
            long timestamp = System.currentTimeMillis();
            int writed_local = 0;
            while ((count = in.read(data, 0, IOHelper.BUFFER_SIZE)) != -1) {
                fout.write(data, 0, count);
                writed_local += count;
                if (System.currentTimeMillis() - timestamp > INTERVAL) {
                    chanheTrack.accept(writed_local);
                    LogHelper.debug("Downloaded %d", writed_local);
                }
            }
            chanheTrack.accept(writed_local);
        }
    }

    public static void downloadZip(URL url, Path dir) throws IOException {
        try (ZipInputStream input = IOHelper.newZipInput(url)) {
            for (ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (entry.isDirectory())
                    continue; // Skip directories
                // Unpack entry
                String name = entry.getName();
                LogHelper.subInfo("Downloading file: '%s'", name);
                IOHelper.transfer(input, dir.resolve(IOHelper.toPath(name)));
            }
        }
    }
}
