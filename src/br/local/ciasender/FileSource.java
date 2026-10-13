package br.local.ciasender;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** A {@link FileServer.Source} backed by a regular file on the file system. */
public final class FileSource implements FileServer.Source {
    private final File file;

    public FileSource(File file) {
        this.file = file;
    }

    public String getName() {
        return file.getName();
    }

    public long length() {
        return file.length();
    }

    public InputStream open(long offset) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            if (offset > 0) in.getChannel().position(offset);
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return in;
    }
}
